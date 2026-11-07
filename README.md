# codist-behaviour

Part of [Codist](https://github.com/AbiXnash/codist). It runs the methods a commit changed on the code before and
after the commit, with the same inputs, and reports what a caller would see differently:

```
Cart.total(1000)
  before  1200
  after   1201
```

A person reads each difference and decides whether it was meant. Nothing here writes tests or changes code.

## How

1. Find the methods the commit changed, and the methods in the same module that call them (through
   [codist-java](https://github.com/AbiXnash/codist-java)'s call graph).
2. Check out the commit and its parent into a scratch folder.
3. Build the module both times. A Maven project is built by Maven (the project's `mvnw`, or `mvn`), together with
   the sibling modules it needs; a project with no build file is compiled with `javac`.
4. Run the fixed harness in `Harness.java`, embedded here: it calls each method with standard edge cases per
   parameter type, on both versions in separate class loaders, and compares the return value, the exception, and
   the arguments after the call. The old version also runs against itself, so results that change from run to run
   are left out, and so are differences only in private fields or only because the harness passed null.

Measured on real bugs and refactors in [codist-eval](https://github.com/AbiXnash/codist-eval): the real difference
showed in 4 of 10 bugs, and no false alarm in 22 refactors.

## Safety

This runs the project's code. The code runs in a scratch folder, with an environment cut down to the path, the JDK
and the locale, so cloud keys, tokens and database addresses in your environment do not reach it. Codist only runs
it when a repository turns it on (`behaviour = true`).

## Needs

A JDK 17 or later (`java` and `javac`), and for Maven projects the project's `mvnw` or `mvn`. Gradle is not supported
yet. Anything missing is reported as the reason a commit was skipped.

```go
import behaviour "github.com/AbiXnash/codist-behaviour"

r := &behaviour.Runner{Repo: repo, Work: dir}
report := r.Run(ctx, sha) // report.Differences, or report.Reason when skipped
```

```bash
go test -tags 'grammar_subset grammar_subset_java' ./...
```
