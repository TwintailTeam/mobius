# mobius

Manifest generator for [TwintailLauncher](https://github.com/TwintailTeam). It calls a game
launcher's own API, takes out what the launcher needs to install and patch the game, and
writes it as a zstd compressed protobuf manifest (`.ttm`) plus the `repository.json` index
the launcher reads.

Which endpoints a provider calls, and which API field ends up in which proto field, are
declared in YAML. Adding a game or a whole new vendor is config work. You don't touch the
Java for it.

## Build

Needs JDK 26.

```
./gradlew jar
```

You get `mobius.jar`, `mobius.desc` and a copy of `configs/` in `build/libs/`, which is
everything a run needs. Don't run `gradlew clean` if you keep manifests in there. It takes
the whole directory with it.

## Run

```
java -jar build/libs/mobius.jar help
java -jar build/libs/mobius.jar games
java -jar build/libs/mobius.jar encode --out manifests
java -jar build/libs/mobius.jar encode hk4e_global --out manifests
java -jar build/libs/mobius.jar decode --out manifests --json-out dump
```

Name one or more biz (`hk4e_global`, `nap_cn`, ...) to work on just those, or name none for
everything the providers serve. `encode` adds to the manifest that is already there, so
version history built by `port` survives a re-encode.

`--configs <dir>` points mobius at your own config tree instead of the `configs/` next to
the jar. `--save-responses <dir>` keeps every API response of a run and `--offline <dir>`
replays them, so you can work on a schema without hammering someone's API.

## Configs

```
configs/
  mobius.yml          which providers to load, which repositories to write, http settings
  providers/          one per vendor API: endpoints, auth, signing, the game catalog
  schema/             how a provider's responses map onto proto fields
  static_configs/     per game data no API serves: features, steps, art, runner hints
```

The tree in this repo is gitignored, because ours holds vendor endpoints and signing
material. Bring your own. Put it next to the jar, pass `--configs`, or drop it in
`src/main/resources/configs/` to run from the IDE.

The protos in `proto/` are the contract with the launcher. Field numbers are the wire
format, so add fields and never renumber them.

Schema reference and a walkthrough for adding a game live in the upcoming wiki.
