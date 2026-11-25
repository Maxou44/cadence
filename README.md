# Cadence

App Android pour piloter les vélos d'appartement **Domyos E Connected** et jouer les
programmes d'entraînement d'origine, en remplacement de l'app Decathlon qui n'est plus maintenue.

Interface en **Jetpack Compose + Material 3** officiels : couleurs dynamiques Material You,
composants et icônes Material.

## Fonctionnalités

- **Appairage** : recherche des consoles `Domyos-…` et FTMS. Le vélo est mémorisé et
  l'app s'y reconnecte automatiquement.
- **Programmes Decathlon** : plus de 300 programmes vélo, filtrés selon le modèle détecté,
  avec catégories, favoris, compteur de séances et détail des paliers.
- **Lecture fidèle à l'app d'origine** :
  - la résistance change à chaque palier ;
  - les ajustements faits dans l'app ou sur la console sont conservés pour la suite ;
  - les cibles de cadence et les consignes s'affichent.
- **Séance libre**, **pause automatique** quand on arrête de pédaler, **historique**.
- **Arrière-plan** : un service de premier plan (`connectedDevice`) garde la liaison
  Bluetooth et le programme actifs écran éteint. Une notification permet de contrôler la
  séance, et l'app se reconnecte seule si la liaison coupe.

## Architecture

| Module | Rôle |
|---|---|
| `domyos-ble-core` | **Couche de communication**, en Kotlin pur sans dépendance Android : protocole propriétaire Domyos + FTMS, `ExerciseBike`, catalogue des modèles. Testée. |
| `domyos-ble-android` | Transport Android : file d'opérations GATT, scan, connexion. |
| `app` | `program/` (parseur, paliers, lecteur, testé) · `session/` (connexion, séance, service) · `ui/` (Compose). |

Le protocole est documenté dans [`PROTOCOL.md`](PROTOCOL.md).

## Compiler

```bash
./gradlew :domyos-ble-core:test :app:testReleaseUnitTest   # tests
./gradlew :app:assembleRelease                              # APK
```

La CI (GitHub Actions) compile l'APK à chaque push ou PR. À chaque push sur `master`, elle
publie aussi une **release GitHub** (`v1.0.N`). Pour installer la dernière version depuis le
téléphone : <https://github.com/Maxou44/cadence/releases/latest/download/cadence.apk>
(autoriser l'installation depuis le navigateur la première fois).

### Signature stable (pour installer les mises à jour par-dessus)

Sans configuration, la CI signe avec une clé de debug **différente à chaque build**. Il faut
alors désinstaller l'app avant d'installer une nouvelle version. Pour une clé fixe :

```bash
keytool -genkeypair -keystore release.jks -alias cadence -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # → secret KEYSTORE_BASE64
```

Ajoutez ensuite dans *Settings › Secrets › Actions* : `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`cadence`) et `KEY_PASSWORD`.

## Notes

- Fermez l'app Decathlon avant de vous connecter : la console n'accepte qu'une connexion à la fois.
- Les programmes (`app/src/main/assets/programs`) proviennent de l'app Decathlon E Connected.
  Ils sont réservés à un usage personnel : gardez ce dépôt **privé**.
