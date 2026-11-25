/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import static com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlanTest.stub;
import static com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlanTest.unexpected;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.BmKey;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.BmPlan;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Source;
import com.ditrix.edt.mcp.server.tools.reference.ScopedReferenceSearch.BmTarget;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Address;

public class ScopedReferenceSearchTest
{
    @Test
    public void everyBmTargetKindAdmitsBeforeDereference()
    {
        var base = namespace("Base");
        var extension = namespace("Extension");
        var sourceAddress = new Address("Base", "A.mdo");
        var excluded = new Address("Base", "Other.mdo");
        var key = new BmKey(base, 1);
        var plan = new BmPlan(Map.of(key, new Source(null, sourceAddress, true, 0, 0, 0),
            new BmKey(base, 2), new Source(null, excluded, false, 0, 0, 0)),
            Set.of(), Set.of(base), Set.of(sourceAddress), List.of());
        var kindList = List.of("metadata", "produced_type", "predefined_item", "field", "metadata");
        var reads = new AtomicInteger();
        var tx = stub(IBmPlatformTransaction.class, (name, args) -> {
            if ("getReferences".equals(name))
            {
                assertEquals(base, args[1]);
                return List.of(reference(base, 1, reads), reference(base, 2, null), reference(extension, 1, null));
            }
            return unexpected(name);
        });
        var targets = kindList.stream().map(kind -> new BmTarget(stub(IBmObject.class, (name, args) ->
            "bmGetNamespace".equals(name) ? base : unexpected(name)), kind)).toList();
        var result = result();
        ScopedReferenceSearch.collectBm(tx, base, targets, plan, result);

        assertEquals(kindList.size(), reads.get());
        assertEquals(Map.of("Base", kindList.size()), result.finish().bmSourceReads());
        assertEquals(List.of(sourceAddress), result.finish().effectiveCheckedScope());
        assertTrue(result.finish().complete());
    }

    @Test
    public void unmappedBmReferenceLeavesCoverageIncompleteWithoutReadingTheSource()
    {
        var base = namespace("Base");
        var address = new Address("Base", "A.mdo");
        var plan = new BmPlan(Map.of(new BmKey(base, 1), new Source(null, address, true, 0, 0, 0)),
            Set.of(), Set.of(base), Set.of(address), List.of());
        var tx = stub(IBmPlatformTransaction.class, (name, args) -> switch (name)
        {
            case "getReferences" -> List.of(reference(base, 99, null));
            case "peekEClass" -> null;
            default -> unexpected(name);
        });
        var target = new BmTarget(stub(IBmObject.class, (name, args) -> unexpected(name)), "metadata");
        var result = result();
        ScopedReferenceSearch.collectBm(tx, base, List.of(target), plan, result);
        assertEquals("resource_unavailable", result.finish().stopReason());
        assertEquals(0, result.finish().examinedResourceCount());
        assertTrue(result.finish().bmSourceReads().isEmpty());
    }

    @Test
    public void generatedContainmentAndExcludedCollisionsDoNotConsumeResultLimit()
    {
        var base = namespace("Base");
        var address = new Address("Base", "A.mdo");
        var plan = new BmPlan(Map.of(new BmKey(base, 1), new Source(null, address, true, 0, 0, 0)),
            Set.of(new BmKey(base, 2)), Set.of(base), Set.of(address), List.of());
        var reads = new AtomicInteger();
        var references = new ArrayList<IBmCrossReference>();
        for (String name : List.of("producedTypes", "formContext", "dbViewDefs", "standardCommands"))
        {
            var containment = EcoreFactory.eINSTANCE.createEReference();
            containment.setName(name);
            references.add(reference(base, 1, reads, containment));
        }
        references.add(reference(base, 2, null));
        references.add(reference(base, 1, reads));
        var tx = stub(IBmPlatformTransaction.class, (name, args) -> "getReferences".equals(name)
            ? references : unexpected(name));
        var target = new BmTarget(stub(IBmObject.class, (name, args) ->
            "bmGetNamespace".equals(name) ? base : unexpected(name)), "metadata");
        var result = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 1, 45, () -> 0);
        ScopedReferenceSearch.collectBm(tx, base, List.of(target), plan, result);
        assertEquals(5, reads.get());
        assertEquals(1, result.finish().references().size());
        assertEquals(List.of(address), result.finish().effectiveCheckedScope());
        assertTrue(result.finish().complete());
    }

    @Test
    public void includesLegacyProducedTypeWrapperAndNestedTargetUris()
    {
        var wrapper = EcoreFactory.eINSTANCE.createEClass();
        var resource = new ResourceImpl(URI.createURI("test:/types"));
        resource.getContents().add(wrapper);
        var item = mock(IBmObject.class, withSettings().extraInterfaces(InternalEObject.class));
        when(item.eResource()).thenReturn(new ResourceImpl(URI.createURI("test:/nested"))
        {
            @Override
            public String getURIFragment(org.eclipse.emf.ecore.EObject object)
            {
                return "item";
            }
        });
        assertEquals(Set.of(EcoreUtil.getURI(wrapper), URI.createURI("test:/nested#item")),
            ScopedReferenceSearch.bslUris(List.of(new BmTarget(item, "produced_type", wrapper))));
    }

    @Test
    public void skipsBslOfRelatedExtensionUntilAdoptedTargetsAreResolved()
    {
        var file = stub(IFile.class, (method, unused) -> "getFileExtension".equals(method) ? "bsl" : unexpected(method));
        var base = new Source(file, new Address("Base", "Base.bsl"), true, 0, 0, 0);
        var linked = new Source(file, new Address("Extension", "Extension.bsl"), true, 0, 0, 0);
        assertEquals(List.of(base), ScopedReferenceSearch.bslSources(List.of(base, linked), Set.of("Extension"), Set.of()));
        assertEquals(List.of(base, linked),
            ScopedReferenceSearch.bslSources(List.of(base, linked), Set.of("Extension"), Set.of("Extension")));
    }

    @Test
    public void rejectsMissingDeclaredProducedTypesAndWrappersWithoutNestedTargets()
    {
        var target = mock(MdObject.class, withSettings().extraInterfaces(IBmObject.class));
        var type = EcoreFactory.eINSTANCE.createEClass();
        var feature = EcoreFactory.eINSTANCE.createEReference();
        feature.setName("producedTypes");
        type.getEStructuralFeatures().add(feature);
        when(target.eClass()).thenReturn(type);
        assertNull(ScopedReferenceSearch.targets(target, result()));

        var wrapper = mock(MdType.class);
        var produced = mock(MdTypes.class);
        when(produced.eContents()).thenReturn(new BasicEList<>(List.of(wrapper)));
        when(target.eGet(feature)).thenReturn(produced);
        assertNull(ScopedReferenceSearch.targets(target, result()));
    }

    @Test
    public void dispatchesOnlyAdmittedUriAndCountsAnIndexedZeroHitFile()
    {
        var address = new Address("Base", "Root.bsl");
        var dispatched = new ArrayList<URI>();
        var lookups = new AtomicInteger();
        var result = result();
        ScopedReferenceSearch.scanBslFile(address, Set.of(URI.createURI("bm://Base/Catalog.Catalog#/")),
            finder(dispatched), index(address.uri(), lookups, Integer.MAX_VALUE), null, result);

        assertEquals(List.of(address.uri()), dispatched);
        assertEquals(2, lookups.get());
        assertEquals(List.of(address), result.finish().effectiveCheckedScope());
        assertTrue(result.finish().bslSourceLoads().isEmpty());
        assertTrue(result.finish().references().isEmpty());
        assertTrue(result.finish().complete());
    }

    @Test
    public void missingIndexDoesNotDispatchAndEvictedDescriptionDoesNotCount()
    {
        var address = new Address("Base", "Root.bsl");
        for (int maxIndexReads : List.of(0, 1))
        {
            var dispatched = new ArrayList<URI>();
            var result = result();
            ScopedReferenceSearch.scanBslFile(address, Set.of(), finder(dispatched),
                index(address.uri(), new AtomicInteger(), maxIndexReads), null, result);
            assertEquals(maxIndexReads, dispatched.size());
            assertEquals(0, result.finish().examinedResourceCount());
            assertEquals("index_unavailable", result.finish().stopReason());
        }
    }

    @Test
    public void changedIndexDescriptionAtTheSameUriDoesNotCountAsCoverage()
    {
        var address = new Address("Base", "Root.bsl");
        var counters = new AtomicInteger();
        var index = stub(IResourceDescriptions.class, (name, args) -> {
            assertEquals("getResourceDescription", name);
            int occurrences = counters.incrementAndGet();
            var reference = stub(IReferenceDescription.class, (method, unused) -> switch (method)
            {
                case "getSourceEObjectUri" -> address.uri().appendFragment("source");
                case "getTargetEObjectUri" -> URI.createURI("test:/Target#object");
                case "getContainerEObjectURI" -> address.uri().appendFragment("container");
                case "getEReference" -> null;
                case "getIndexInList" -> -1;
                default -> unexpected(method);
            });
            return stub(IResourceDescription.class, (method, unused) -> switch (method)
            {
                case "getURI" -> address.uri();
                case "getImportedNames" -> List.of(QualifiedName.create("SameImportedName"));
                case "getExportedObjects" -> List.of();
                case "getReferenceDescriptions" -> java.util.Collections.nCopies(occurrences, reference);
                default -> unexpected(method);
            });
        });
        var result = result();
        ScopedReferenceSearch.scanBslFile(address, Set.of(), finder(new ArrayList<>()), index, null, result);
        assertEquals("index_unavailable", result.finish().stopReason());
        assertEquals(0, result.finish().examinedResourceCount());
    }

    private static ReferenceSearchResult result()
    {
        return new ReferenceSearchResult("Base", "Catalog.Catalog", null, 10, 45, () -> 0);
    }

    private static IResourceDescriptions index(URI uri, AtomicInteger calls, int maxReads)
    {
        return stub(IResourceDescriptions.class, (method, args) -> {
            if (!"getResourceDescription".equals(method))
            {
                return unexpected(method);
            }
            assertEquals(uri, args[0]);
            return calls.incrementAndGet() > maxReads ? null
                : stub(IResourceDescription.class, (access, unused) -> switch (access)
                {
                    case "getURI" -> uri;
                    case "getExportedObjects", "getImportedNames", "getReferenceDescriptions" -> List.of();
                    default -> unexpected(access);
                });
        });
    }

    private static IReferenceFinder finder(List<URI> dispatched)
    {
        return stub(IReferenceFinder.class, (method, args) -> {
            if (!"findReferences".equals(method))
            {
                return unexpected(method);
            }
            for (Object source : (Iterable<?>)args[1])
            {
                dispatched.add((URI)source);
            }
            return null;
        });
    }

    private static IBmNamespace namespace(String label)
    {
        var engine = stub(IBmEngine.class, (name, args) -> "getObjectResourceId".equals(name)
            ? ((Long)args[0]).intValue() : unexpected(name));
        return stub(IBmNamespace.class, (name, args) -> switch (name)
        {
            case "getName" -> label;
            case "asEngine" -> engine;
            default -> unexpected(name);
        });
    }

    private static IBmCrossReference reference(IBmNamespace namespace, long id, AtomicInteger reads)
    {
        return reference(namespace, id, reads, null);
    }

    private static IBmCrossReference reference(IBmNamespace namespace, long id, AtomicInteger reads,
        EStructuralFeature containment)
    {
        return stub(IBmCrossReference.class, (name, args) -> switch (name)
        {
            case "getNamespace" -> namespace;
            case "getObjectId" -> id;
            case "getFeature" -> null;
            case "getObject" ->
            {
                if (reads == null)
                {
                    yield unexpected(name);
                }
                reads.incrementAndGet();
                yield stub(IBmObject.class, (objectMethod, unused) -> switch (objectMethod)
                {
                    case "eClass" -> EcoreFactory.eINSTANCE.createEClass();
                    case "bmGetTopObject", "bmGetUri", "eContainer" -> null;
                    case "eContainmentFeature" -> containment;
                    case "bmGetId" -> id;
                    default -> unexpected(objectMethod);
                });
            }
            default -> unexpected(name);
        });
    }
}
