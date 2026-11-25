/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResourceFactory;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.PredefinedItemUtil;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeSet;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSearchResult.Occurrence;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Address;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.BmPlan;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Source;
import com.ditrix.edt.mcp.server.utils.AdoptedReferenceTargets;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslReferenceSearch;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SelectedExtension;

/** Read-only semantic search with independent target resolution and source admission. */
@SuppressWarnings("restriction")
public final class ScopedReferenceSearch
{
    record BmTarget(IBmObject object, String kind, EObject bslWrapper)
    {
        BmTarget(IBmObject object, String kind)
        {
            this(object, kind, null);
        }
    }
    private record IndexedReference(URI source, URI target, URI container, URI feature, int listIndex) {}
    private record IndexSignature(Set<URI> exports, Set<String> imports, Map<IndexedReference, Integer> references) {}
    private static final Set<String> GENERATED_FEATURES =
        Set.of("producedTypes", "formContext", "dbViewDefs", "standardCommands");

    public Object run(String projectName, String objectFqn, ReferenceSourceScope scope,
        ReferenceSearchResult result) throws InterruptedException
    {
        result.check();
        IProject base = ProjectContext.of(projectName).project();
        if (base == null || !base.exists())
        {
            throw new IllegalArgumentException(ProjectContext.notFoundMessage(projectName));
        }
        try
        {
            var selection = scope == null ? null : scope.resolve(result::check);
            List<IProject> requested = selection == null ? List.of(base) : selection.projects();
            if (!base.isAccessible())
            {
                result.resourceUnavailable(projectName + ": target project is closed");
                return result.finish();
            }
            List<IProject> ready = awaitProjects(base, requested, result);
            if (!ready.contains(base))
            {
                return result.finish();
            }
            var dependencies = scope == null ? awaitDefaultDependencies(base, result) : null;
            Map<IProject, SelectedExtension> extensions = new HashMap<>();
            List<IProject> sources = scope == null ? List.of(base)
                : selectedSources(base, requested, ready, extensions, result);
            boolean changedWhileWaiting = selection != null
                && !selection.matches().equals(scope.selectorMatches(requested, result::check));
            ReferenceSourcePlan plan = ReferenceSourcePlan.capture(sources, scope, result::stopped);
            plan.unavailable().forEach(result::resourceUnavailable);
            IBmModelManager manager = Activator.getDefault().getBmModelManager();
            if (manager == null)
            {
                result.resourceUnavailable("BM model manager is unavailable");
                return result.finish();
            }
            Set<URI> targetUris = new LinkedHashSet<>();
            Map<String, Set<URI>> adoptedUris = new HashMap<>();
            String fqn = MetadataTypeUtils.normalizeFqn(objectFqn);
            Boolean targetFound = manager.executeReadOnlyTask(tx -> {
                result.check();
                IBmNamespace baseNamespace = manager.getBmNamespace(base);
                if (baseNamespace == null)
                {
                    result.resourceUnavailable(projectName + ": BM namespace is unavailable");
                    return true;
                }
                IBmObject baseObject = tx.getTopObjectByFqn(baseNamespace, fqn);
                if (!(baseObject instanceof MdObject target))
                {
                    return false;
                }
                List<BmTarget> targets = targets(target, result);
                if (targets == null)
                {
                    result.resourceUnavailable(projectName + ": produced reference targets are unavailable for " + fqn);
                    return true;
                }
                targetUris.addAll(bslUris(targets));
                if (scope == null)
                {
                    var adopted = AdoptedReferenceTargets.resolve((IBmObject)target, dependencies);
                    targetUris.addAll(adopted.getTargetURIs());
                    if (!adopted.isComplete())
                    {
                        result.indexUnavailable(adopted.getFailureReason());
                    }
                }
                Map<IProject, IBmNamespace> namespaces = new HashMap<>();
                namespaces.put(base, baseNamespace);
                for (IProject source : sources)
                {
                    result.check();
                    if (!source.equals(base))
                    {
                        namespaces.put(source, manager.getBmNamespace(source));
                    }
                }
                BmPlan bm = plan.bind(namespaces::get, tx, result::stopped);
                bm.unavailable().forEach(result::resourceUnavailable);
                for (IProject source : sources)
                {
                    result.check();
                    IBmNamespace namespace = namespaces.get(source);
                    boolean related = extensions.get(source) == SelectedExtension.RELATED;
                    if (namespace == null)
                    {
                        if (related)
                        {
                            result.resourceUnavailable(source.getName() + ": BM namespace for adopted targets is unavailable");
                        }
                        continue;
                    }
                    List<BmTarget> sourceTargets = new ArrayList<>(targets);
                    if (related)
                    {
                        List<BmTarget> adoptedTargets = adoptedTargets(target, namespace, tx, result);
                        if (adoptedTargets == null)
                        {
                            result.resourceUnavailable(source.getName() + ": adopted targets are unavailable for " + fqn);
                            continue;
                        }
                        sourceTargets.addAll(adoptedTargets);
                        adoptedUris.put(source.getName(), bslUris(adoptedTargets));
                    }
                    if (bm.planned().stream().anyMatch(address -> address.project().equals(source.getName())))
                    {
                        collectBm(tx, namespace, sourceTargets, bm, result);
                    }
                }
                return true;
            });
            if (targetFound == null)
            {
                result.resourceUnavailable("EDT did not return a BM scan result");
            }
            else if (!targetFound)
            {
                throw new IllegalArgumentException("Object not found: " + objectFqn
                    + ". Supply a top-level metadata object FQN.");
            }
            if (!targetUris.isEmpty())
            {
                if (scope == null)
                {
                    collectLegacyBsl(base, dependencies, targetUris, result);
                }
                else
                {
                    var related = extensions.entrySet().stream()
                        .filter(entry -> entry.getValue() == SelectedExtension.RELATED)
                        .map(entry -> entry.getKey().getName()).collect(java.util.stream.Collectors.toSet());
                    collectBsl(plan, targetUris, adoptedUris, related, result);
                }
            }
            if (changedWhileWaiting || !plan.sameFiles(ReferenceSourcePlan.capture(sources, scope, result::stopped))
                || ready.stream().anyMatch(project -> !ProjectStateChecker.checkReferenceSearchProject(project).isReady())
                || (selection != null && !selection.matches().equals(scope.selectorMatches(requested, result::check)))
                || extensions.entrySet().stream().anyMatch(entry ->
                    ProjectStateChecker.classifySelectedExtension(base, entry.getKey()) != entry.getValue()))
            {
                result.invalidate("Observed a change in the selected paths, source inventory or EDT readiness");
            }
        }
        catch (OperationCanceledException stopped)
        {
            if (!result.stopped())
            {
                result.indexUnavailable("EDT interrupted the reference scan");
            }
        }
        return result.finish();
    }

    private static List<IProject> selectedSources(IProject base, List<IProject> requested, List<IProject> ready,
        Map<IProject, SelectedExtension> extensions, ReferenceSearchResult result)
    {
        List<IProject> sources = new ArrayList<>();
        for (IProject source : requested)
        {
            result.check();
            if (!ready.contains(source))
            {
                continue;
            }
            SelectedExtension relationship = ProjectStateChecker.classifySelectedExtension(base, source);
            extensions.put(source, relationship);
            if (relationship == SelectedExtension.UNDETERMINED)
            {
                result.resourceUnavailable(source.getName() + ": relationship to the target project is unavailable");
            }
            else
            {
                sources.add(source);
            }
        }
        return sources;
    }

    private static ProjectStateChecker.SearchDependenciesResult awaitDefaultDependencies(IProject base,
        ReferenceSearchResult result) throws InterruptedException
    {
        result.check();
        var dependencies = ProjectStateChecker.determineReferenceSearchDependencies(base);
        if (dependencies.isDetermined() && !dependencies.isAllReady())
        {
            awaitProjects(base, dependencies.getProjects(), result);
            result.check();
            return ProjectStateChecker.determineReferenceSearchDependencies(base);
        }
        return dependencies;
    }

    private static List<IProject> awaitProjects(IProject base, List<IProject> requested,
        ReferenceSearchResult result) throws InterruptedException
    {
        var projects = new LinkedHashSet<>(requested);
        projects.add(base);
        var ready = new ArrayList<IProject>();
        result.waitingForIndex(true);
        for (IProject project : projects)
        {
            result.check();
            var state = ProjectStateChecker.checkReferenceSearchProject(project);
            while (state.getState() == ProjectState.BUILDING)
            {
                Thread.sleep(40);
                result.check();
                state = ProjectStateChecker.checkReferenceSearchProject(project);
            }
            if (state.isReady())
            {
                ready.add(project);
            }
            else if (state.getState() == ProjectState.NOT_AVAILABLE)
            {
                result.resourceUnavailable(project.getName() + ": " + state.getMessage());
            }
            else
            {
                result.indexUnavailable(project.getName() + ": " + state.getMessage());
            }
        }
        result.waitingForIndex(false);
        return ready;
    }

    /** Returns null when a declared produced type cannot be queried. */
    static List<BmTarget> targets(MdObject target, ReferenceSearchResult result)
    {
        var targets = new ArrayList<BmTarget>();
        targets.add(new BmTarget((IBmObject)target, "metadata"));
        var produced = MdClassUtil.getProducedTypes(target);
        if (produced == null && target.eClass().getEStructuralFeature("producedTypes") != null)
        {
            return null;
        }
        if (produced != null)
        {
            for (EObject type : produced.eContents())
            {
                result.check();
                TypeItem item = type instanceof MdType single ? single.getType()
                    : type instanceof MdTypeSet set ? set.getTypeSet() : null;
                if (!(item instanceof IBmObject object))
                {
                    return null;
                }
                targets.add(new BmTarget(object, "produced_type", type));
            }
        }
        for (var item : PredefinedItemUtil.getItems(target))
        {
            result.check();
            targets.add(new BmTarget((IBmObject)item, "predefined_item"));
        }
        if (target instanceof FieldSource fields)
        {
            for (var field : fields.getFields())
            {
                result.check();
                if (field instanceof IBmObject object)
                {
                    targets.add(new BmTarget(object, "field"));
                }
            }
        }
        return targets;
    }

    static Set<URI> bslUris(List<BmTarget> targets)
    {
        Set<URI> uris = new LinkedHashSet<>();
        for (BmTarget target : targets)
        {
            uris.add(EcoreUtil.getURI(target.object()));
            if (target.bslWrapper() != null)
            {
                uris.add(EcoreUtil.getURI(target.bslWrapper()));
            }
        }
        uris.remove(null);
        return uris;
    }

    static List<BmTarget> adoptedTargets(MdObject target, IBmNamespace namespace, IBmPlatformTransaction tx,
        ReferenceSearchResult result)
    {
        if (!(target.eClass().getEStructuralFeature("extendedConfigurationObject") instanceof EAttribute feature))
        {
            return List.of();
        }
        UUID uuid = target.getUuid();
        if (uuid == null)
        {
            return null;
        }
        var ids = tx.getObjectIdsByAttributeValue(namespace, feature, uuid);
        while (ids.hasNext())
        {
            result.check();
            if (tx.getObjectById(namespace, ids.next()) instanceof MdObject candidate
                && target.eClass().equals(candidate.eClass()) && uuid.equals(candidate.getExtendedConfigurationObject()))
            {
                return targets(candidate, result);
            }
        }
        return List.of();
    }

    static void collectBm(IBmPlatformTransaction tx, IBmNamespace namespace,
        List<BmTarget> targets, BmPlan plan, ReferenceSearchResult result)
    {
        boolean covered = true;
        for (BmTarget target : targets)
        {
            result.check();
            for (IBmCrossReference ref : tx.getReferences(target.object(), namespace))
            {
                result.check();
                var key = plan.key(ref);
                if (key == null || !namespace.equals(key.namespace()) || plan.ambiguous().contains(key))
                {
                    continue;
                }
                EStructuralFeature feature = ref.getFeature();
                if (feature != null && feature.isTransient())
                {
                    continue;
                }
                Source source = plan.unique().get(key);
                if (source == null)
                {
                    if (!derived(tx.peekEClass(namespace, ref.getObjectId())))
                    {
                        covered = false;
                        result.resourceUnavailable(namespace.getName() + ": unmapped BM resource " + key.resourceId());
                    }
                    continue;
                }
                if (!source.selected())
                {
                    continue;
                }
                result.sourceLoad(source.address().project(), false);
                IBmObject object = ref.getObject();
                if (object == null)
                {
                    covered = false;
                    result.resourceUnavailable(source.address().uri().toString());
                }
                else if (!derived(object.eClass()) && !generatedContainment(object, feature, result))
                {
                    result.add(modelOccurrence(source.address(), object, feature, target));
                }
            }
        }
        if (covered)
        {
            plan.unique().entrySet().stream().filter(entry -> namespace.equals(entry.getKey().namespace()))
                .map(java.util.Map.Entry::getValue).filter(Source::selected).map(Source::address)
                .forEach(result::examined);
        }
    }

    private static boolean derived(EClass type)
    {
        String uri = type == null || type.getEPackage() == null ? "" : type.getEPackage().getNsURI();
        return uri != null && (uri.contains("dbview") || (uri.contains("cmi") && uri.contains("deriveddata")));
    }

    private static boolean generatedContainment(EObject object, EStructuralFeature referenceFeature,
        ReferenceSearchResult result)
    {
        if (referenceFeature != null && GENERATED_FEATURES.contains(referenceFeature.getName()))
        {
            return true;
        }
        Set<EObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EObject current = object; current != null && visited.add(current); current = current.eContainer())
        {
            result.check();
            var feature = current.eContainmentFeature();
            if (feature != null && GENERATED_FEATURES.contains(feature.getName()))
            {
                return true;
            }
        }
        return false;
    }

    private static Occurrence modelOccurrence(Address address, IBmObject object, EStructuralFeature feature,
        BmTarget target)
    {
        IBmObject top = object.bmGetTopObject();
        String owner = top == null ? null : top.bmGetFqn();
        URI uri = object.bmGetUri();
        String fragment = uri != null && uri.fragment() != null ? uri.fragment()
            : top == null ? Long.toString(object.bmGetId()) : EcoreUtil.getRelativeURIFragmentPath(top, object);
        String property = feature == null ? null : feature.getName();
        IBmNamespace targetNamespace = target.object().bmGetNamespace();
        String targetIdentity = feature != null && feature.isMany() && targetNamespace != null
            ? ":" + targetNamespace.getName() + ":" + target.object().bmGetId() : "";
        return new Occurrence(address.uri() + "#bm:" + fragment + ":" + property + targetIdentity, address.project(),
            address.path(), target.kind(), owner, property, fragment, null, null, null, null);
    }

    static List<Source> bslSources(List<Source> files, Set<String> related, Set<String> checkedAdoptions)
    {
        return files.stream().filter(Source::selected).filter(Source::bsl).filter(source -> {
            String project = source.address().project();
            return !related.contains(project) || checkedAdoptions.contains(project);
        }).toList();
    }

    private static void collectBsl(ReferenceSourcePlan plan, Set<URI> targets,
        Map<String, Set<URI>> adoptedUris, Set<String> related, ReferenceSearchResult result)
    {
        List<Source> sources = bslSources(plan.files(), related, adoptedUris.keySet());
        if (sources.isEmpty())
        {
            return;
        }
        var provider = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleUtils.BSL_LOOKUP_URI);
        var index = provider == null ? null : provider.get(IResourceDescriptions.class);
        var finder = provider == null ? null : provider.get(IReferenceFinder.class);
        if (finder == null || index == null)
        {
            result.indexUnavailable("Xtext reference finder or index is unavailable");
            return;
        }
        Map<String, Set<URI>> targetsByProject = new HashMap<>();
        for (Source source : sources)
        {
            result.check();
            try
            {
                Set<URI> projectTargets = targetsByProject.computeIfAbsent(source.address().project(), project -> {
                    var extended = new LinkedHashSet<>(targets);
                    extended.addAll(adoptedUris.getOrDefault(project, Set.of()));
                    return extended;
                });
                scanBslFile(source.address(), projectTargets, finder, index, provider, result);
            }
            catch (OperationCanceledException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                Activator.logError("Xtext reference scan failed for " + source.address().uri(), e);
                result.indexUnavailable("Xtext scan failed for " + source.address().uri());
            }
        }
    }

    static void scanBslFile(Address address, Set<URI> targets, IReferenceFinder finder,
        IResourceDescriptions index, IResourceServiceProvider provider, ReferenceSearchResult result)
    {
        URI uri = address.uri();
        var before = index.getResourceDescription(uri);
        if (before == null || !uri.equals(before.getURI()))
        {
            result.indexUnavailable("No current Xtext resource description for " + uri);
            return;
        }
        var beforeSignature = signature(before, result);
        var access = new AdmittedModule(address, provider, result);
        finder.findReferences(targets, List.of(uri), access, ref -> {
            result.check();
            URI source = ref.getSourceEObjectUri();
            if (source != null && uri.equals(source.trimFragment()))
            {
                result.add(bslOccurrence(address, ref, access));
            }
            else
            {
                access.unexpected();
            }
        }, monitor(result));
        result.check();
        var after = index.getResourceDescription(uri);
        if (after == null || !uri.equals(after.getURI()) || !access.isValid()
            || !beforeSignature.equals(signature(after, result)))
        {
            result.indexUnavailable("Xtext source changed or could not be examined: " + uri);
            return;
        }
        result.examined(address);
    }

    private static IndexSignature signature(IResourceDescription description, ReferenceSearchResult result)
    {
        Set<URI> exports = new LinkedHashSet<>();
        for (var object : description.getExportedObjects())
        {
            result.check();
            exports.add(object.getEObjectURI());
        }
        Set<String> imports = new LinkedHashSet<>();
        for (var name : description.getImportedNames())
        {
            result.check();
            imports.add(name.toString());
        }
        Map<IndexedReference, Integer> references = new HashMap<>();
        for (var reference : description.getReferenceDescriptions())
        {
            result.check();
            var feature = reference.getEReference();
            var key = new IndexedReference(reference.getSourceEObjectUri(), reference.getTargetEObjectUri(),
                reference.getContainerEObjectURI(), feature == null ? null : EcoreUtil.getURI(feature),
                reference.getIndexInList());
            references.merge(key, 1, Integer::sum);
        }
        return new IndexSignature(exports, imports, references);
    }

    private static void collectLegacyBsl(IProject base, ProjectStateChecker.SearchDependenciesResult dependencies,
        Set<URI> targets, ReferenceSearchResult result)
    {
        var provider = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleUtils.BSL_LOOKUP_URI);
        var finder = provider == null ? null : provider.get(IReferenceFinder.class);
        var index = provider == null ? null : provider.get(IResourceDescriptions.class);
        if (finder == null || index == null)
        {
            result.indexUnavailable("Xtext reference finder or index is unavailable");
            return;
        }
        var inventory = ReferenceSourcePlan.captureBsl(dependencies.getProjects(), result::stopped);
        inventory.unavailable().forEach(result::resourceUnavailable);
        Map<Address, IndexSignature> signatures = new HashMap<>();
        for (Source source : inventory.files())
        {
            result.check();
            IndexSignature snapshot = signatureAt(index, source.address(), result);
            if (snapshot != null)
            {
                signatures.put(source.address(), snapshot);
            }
        }
        // With no sourceScope, preserve the legacy dependency set and its workspace fallback.
        var modules = new ConcurrentHashMap<Address, AdmittedModule>();
        var examined = new java.util.concurrent.atomic.AtomicBoolean();
        boolean stable = BslReferenceSearch.findReferences(provider, finder, base, targets, ref -> {
            result.check();
            Address address = address(ref.getSourceEObjectUri());
            if (address != null)
            {
                result.add(bslOccurrence(address, ref, modules.computeIfAbsent(address,
                    value -> new AdmittedModule(value, provider, result))));
            }
            else
            {
                result.indexUnavailable("A BSL source has no workspace address");
            }
        }, monitor(result), dependencies, candidates -> {
            examined.set(true);
            Set<URI> indexed = Set.copyOf(candidates);
            for (Source source : inventory.files())
            {
                result.check();
                Address address = source.address();
                var before = signatures.get(address);
                var module = modules.get(address);
                if (indexed.contains(address.uri()) && before != null
                    && before.equals(signatureAt(index, address, result)) && (module == null || module.isValid()))
                {
                    result.examined(address);
                }
                else
                {
                    result.indexUnavailable("Xtext source missing or changed during the search: " + address.uri());
                }
            }
        });
        if (!stable || !examined.get())
        {
            result.indexUnavailable("Could not prove the legacy Xtext source scope remained stable");
        }
        if (!inventory.sameFiles(ReferenceSourcePlan.captureBsl(dependencies.getProjects(), result::stopped)))
        {
            result.invalidate("Observed a change in the default BSL source inventory during the search");
        }
    }

    private static IndexSignature signatureAt(IResourceDescriptions index, Address address, ReferenceSearchResult result)
    {
        URI uri = address.uri();
        var description = index.getResourceDescription(uri);
        return description != null && uri.equals(description.getURI()) ? signature(description, result) : null;
    }

    private static Address address(URI uri)
    {
        if (uri == null || !uri.isPlatformResource() || uri.segmentCount() < 4
            || !"src".equals(uri.segment(2)))
        {
            return null;
        }
        var path = org.eclipse.core.runtime.Path.fromPortableString(uri.toPlatformString(true));
        return new Address(path.segment(0), path.removeFirstSegments(2).toPortableString());
    }

    private static NullProgressMonitor monitor(ReferenceSearchResult result)
    {
        return new NullProgressMonitor()
        {
            @Override
            public boolean isCanceled()
            {
                return super.isCanceled() || result.stopped();
            }
        };
    }

    private static Occurrence bslOccurrence(Address address, IReferenceDescription reference, AdmittedModule access)
    {
        URI source = reference.getSourceEObjectUri();
        var feature = reference.getEReference();
        String property = feature == null ? null : feature.getName();
        Integer index = reference.getIndexInList() < 0 ? null : reference.getIndexInList();
        String fragment = source.fragment();
        Integer offset = null;
        Integer line = null;
        Integer column = null;
        EObject object = access.object(source);
        var node = object == null ? null : NodeModelUtils.findActualNodeFor(object);
        if (node != null)
        {
            offset = node.getOffset();
            line = node.getStartLine();
            column = NodeModelUtils.getLineAndColumn(node, offset).getColumn();
        }
        return new Occurrence(address.uri() + "#bsl:" + fragment + ":" + property + ":" + index,
            address.project(), address.path(), "bsl", null, property, fragment, index, offset, line, column);
    }

    private static final class AdmittedModule implements IReferenceFinder.ILocalResourceAccess
    {
        private final Address address;
        private final IResourceServiceProvider provider;
        private final ReferenceSearchResult result;
        private ResourceSet resources;
        private volatile boolean valid = true;

        AdmittedModule(Address address, IResourceServiceProvider provider, ReferenceSearchResult result)
        {
            this.address = address;
            this.provider = provider;
            this.result = result;
        }

        @Override
        public synchronized <R> R readOnly(URI uri, IUnitOfWork<R, ResourceSet> work)
        {
            result.check();
            if (uri == null || !address.uri().equals(uri.trimFragment()))
            {
                unexpected();
                return null;
            }
            try
            {
                return work.exec(resources());
            }
            catch (OperationCanceledException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                valid = false;
                return null;
            }
        }

        void unexpected()
        {
            valid = false;
        }

        boolean isValid()
        {
            return valid;
        }

        EObject object(URI uri)
        {
            return readOnly(uri, set -> set.getEObject(uri, true));
        }

        private ResourceSet resources()
        {
            if (resources == null)
            {
                URI admitted = address.uri();
                resources = new ResourceSetImpl()
                {
                    @Override
                    public Resource getResource(URI uri, boolean load)
                    {
                        result.check();
                        if (!admitted.equals(uri.trimFragment()))
                        {
                            if (load && uri.isPlatformResource() && "bsl".equalsIgnoreCase(uri.fileExtension()))
                            {
                                unexpected();
                            }
                            return null;
                        }
                        Resource existing = super.getResource(uri, false);
                        if (load && (existing == null || !existing.isLoaded()))
                        {
                            result.sourceLoad(address.project(), true);
                        }
                        return super.getResource(uri, load);
                    }
                };
                XtextResourceFactory factory = provider.get(XtextResourceFactory.class);
                if (factory == null)
                {
                    throw new IllegalStateException("BSL resource factory is unavailable");
                }
                resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bsl", factory);
            }
            return resources;
        }
    }
}
