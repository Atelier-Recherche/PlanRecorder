# PlanRecorder

[English README](README.en.md)

Fork d’[AudioRecorder](https://github.com/Dimowner/AudioRecorder) (v2.5) pour **prendre des notes audio classées par événement de calendrier** (DAVx⁵ / calendrier Android).

| | |
|---|---|
| Application ID | `com.planrecorder` (debug : `com.planrecorder.debug`) |
| Licence | Apache 2.0 (base AudioRecorder) |
| Icône | [Lucide mic-signal](https://lucide.dev/icons/mic-signal) (ISC) — merci à Lucide |

## Fonctionnalités

1. **Événement en cours** via `CalendarContract` au démarrage de l’enregistrement
2. **Calendrier source** configurable (ou tous les calendriers)
3. **Patterns** dossier / fichier avec balises cliquables (`[id]`, `[ue]`, date/heure…)
4. **Export SAF** vers un dossier racine choisi une fois
5. **Opus** + préréglage **Filtrer pour les voix**
6. **Identifiants personnalisés** (`UE:1+`, `MATH:3`…) + liste des IDs déjà vus
7. **AGC live** et **normalisation peak → WAV** à l’export (options)
8. **Widget « session en cours »** et **rappel X min** avant le créneau
9. **Marqueurs** pendant l’enregistrement → fichier Markdown style Obsidian  
   `[[fichier.opus#t=508|08:28]] debut de session`

## Matching intelligent des dossiers

Si le pattern produit `UE581 session croquette` mais qu’existe déjà `UE581 session croquette et tech`, PlanRecorder **réutilise le dossier existant** (et l’inverse), tant que l’identifiant correspond.

## Sans événement / ambiguïté

- suggestions parmi les dossiers ID déjà présents + IDs mémorisés
- préremplissage possible, modifiable dans le dialogue de fin

## Balises de pattern

| Tag | Signification |
|-----|----------------|
| `[calendar]` | Nom affiché de l’agenda |
| `[title]` | Titre complet |
| `[title_rest]` | Titre sans le préfixe identifiant |
| `[id]` / `[ue]` | Identifiant extrait (`UE323`, …) |
| `[NN]` / `[nn]` | Numéro de séance (01… / 1…) |
| `[dd]` `[mm]` `[yy]` `[yyyy]` | Date |
| `[HH]` `[MM]` `[SS]` | Heure (`[MM]` = minutes, `[mm]` = mois) |

Defaults : dossier `[calendar]/[id] [title_rest]` · fichier `[NN]-T[HH][MM][SS]-[dd][mm][yy]-[id]`

## Build / install

```bash
./gradlew :app:assembleDebugConfigDebug
adb install -r app/build/outputs/apk/debugConfig/debug/app-debugConfig-debug.apk
```

Release locale (artefacts dans `release/`, ignoré par git) :

```powershell
.\scripts\release.ps1
# incrémenter versionName (patch) + versionCode dans build.gradle.kts :
.\scripts\release.ps1 -Bump
# version explicite + tag GitHub :
.\scripts\release.ps1 -Version 1.1.0 -CreateGithubRelease
```

## Première utilisation

1. **Settings → PlanRecorder**
2. Choisir le **dossier d’export** (SAF)
3. Choisir le **calendrier** (ex. DAVx⁵)
4. Ajuster patterns / règles d’identifiant
5. Optionnel : Filtrer pour les voix, AGC, normalisation, rappel
6. Accorder **Calendrier** (+ notifications pour les rappels)
7. Enregistrer pendant un événement, valider le dialogue de fin

## Publier sur un nouveau dépôt GitHub

```bash
# 1. Créer le dépôt (compte GitHub connecté via gh)
gh repo create PlanRecorder --public --source=. --remote=origin --description "Calendar-aware audio notes for sessions (fork of AudioRecorder)"

# 2. Pousser la branche
git push -u origin HEAD

# Ou, si origin existe déjà :
# git remote add origin https://github.com/<toi>/PlanRecorder.git
# git push -u origin HEAD
```

Garder l’upstream AudioRecorder pour les merges :

```bash
git remote add upstream https://github.com/Dimowner/AudioRecorder.git   # si besoin
git fetch upstream
git merge upstream/master
```

Les packages Java restent `com.dimowner.audiorecorder…` pour faciliter les merges ; seul l’`applicationId` / le libellé sont PlanRecorder.

## Crédits

- [AudioRecorder](https://github.com/Dimowner/AudioRecorder) — Dmytro Ponomarenko (Apache 2.0)
- Icône [mic-signal](https://lucide.dev/icons/mic-signal) — [Lucide Icons](https://lucide.dev) (ISC)
