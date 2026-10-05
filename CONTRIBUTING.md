# Contributing

## Commit messages

Subjects follow [Conventional Commits](https://www.conventionalcommits.org):

```
type(optional-scope)!: description
```

The body explains _why_ a change is right rather than restating the diff; the subject
line carries a prefix a tool can read.

| Part          | Rule                                                                                             |
| ------------- | ------------------------------------------------------------------------------------------------ |
| `type`        | one of `build` `chore` `ci` `docs` `feat` `fix` `perf` `refactor` `revert` `style` `test`        |
| `scope`       | optional, lower case: `app` `hub-client` `ui` `build` `deps` `ci` `docs`                         |
| `!`           | append to the type or scope for a breaking change, and explain it in a `BREAKING CHANGE:` footer |
| `description` | lower case, imperative, no trailing full stop, whole subject within 72 characters                |
| body          | separated by one blank line, wrapped at 72, present for anything not self-evident                |

Footers, where they apply: `Fixes: #123`, `Refs: #123`, `BREAKING CHANGE: ...`.

```
fix(ui): keep a switch where it was while its write is pending

A poll that started before the press could land after it and put the
switch back, so for a moment the screen showed a state the hub had
already left.

Fixes: #6
```

### Enable the hook

Once per clone:

```bash
git config core.hooksPath .githooks
git config commit.template .gitmessage
```

`.githooks/commit-msg` rejects a message that does not fit. It is a plain POSIX shell
script, identical apart from its examples to the one the sibling repositories
([pihome-hub](https://github.com/DGPRoman/pihome-hub),
[pihome-hub-web](https://github.com/DGPRoman/pihome-hub-web)) use, and CI runs that same
file over every commit in a pull request.

## Before opening a pull request

```bash
./gradlew spotlessApply                 # format
./gradlew spotlessCheck lint test assembleDebug
```

A change to `:hub-client` should also pass against a real hub:

```bash
scripts/contract-test.sh                # needs a pihome-hub checkout beside this one
```

CI runs the same tasks, and a release build besides, and the contract tests against the hub
commit pinned in `.github/workflows/contract.yml`. When the client follows a change in the
hub, move that pin in the same pull request.

Lint treats warnings as errors; a warning that is wrong for this project is disabled in
`app/build.gradle.kts` with the reason next to it, not suppressed where it fires.
