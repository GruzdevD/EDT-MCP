## Resolve a change for review

    {"repoId":"app","projectName":"Base","branch":"refs/heads/main","changeId":"fix-rate"}

Use the repository and branch from the project's review grant. The server fixes observedHead at request start and walks reachable history. It recognizes exact bracketed and legacy ticket subjects for dev-build and design. phases lists the matching SHAs, trees and parents, including design commits after the final build. buildDeltas contains just the before/after SHAs for each build in order. range covers everything from the parent of the earliest build to the last build, including intervening merges. Use buildDeltas for attribution.

Check complete before consuming the range. An ambiguous build chain, multi-parent build, missing base, absent build or budget (4096 commits, 128 matching phases) yields incomplete history. Supply expectedFinal to detect a change in the final build.

Named change resolution requires the read action for that change on the selected branch.
