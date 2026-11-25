# Protocole Bluetooth des consoles Domyos (E Connected)

Rétro-ingénierie de l'application **Domyos E Connected 5.1.4** (`com.tymate.domyos.connected`),
SDK interne `com.appdevice.domyos` (« DomyosApi ») + couche GATT `com.appdevice.api.ble`.
Aucune bibliothèque native : tout le protocole est en Java/Kotlin et a été lu dans le bytecode.

Les consoles parlent **deux protocoles** :

| Protocole | Service GATT | Utilisé par |
|---|---|---|
| **Domyos propriétaire** | `49535343-FE7D-…` (UART transparent Microchip/ISSC) | quasi toutes les consoles « E » / EB / Bike 500… |
| **FTMS** (standard SIG) | `0x1826` | consoles récentes (Training Bike 900, Challenge, Initial, EB 140 B / 520 B / 900 B) |

Les consoles « B » parlent les deux. L'app d'origine préfère le protocole Domyos, sauf pour une
liste d'ID renvoyée par le backend Decathlon.

---

## 1. Découverte

Scan BLE **sans filtre** (beaucoup de consoles n'annoncent pas d'UUID), puis filtrage sur le nom :

| Préfixe du nom (insensible à la casse) | Type |
|---|---|
| `domyos-bike-`, `domyos-biking-`, `domyos-eb` | vélo |
| `domyos-el-` | elliptique |
| `domyos-tc-` | tapis |
| `domyos-row-`, `domyos-wr`, `pd<chiffres>` | rameur |

Le drapeau « FTMS » est déduit de la présence de `0x1826` dans l'annonce.

## 2. GATT

| Rôle | UUID | Propriété |
|---|---|---|
| Service Domyos | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` | |
| Console → app | `49535343-1E4D-4BD9-BA61-23C647249616` | notify |
| App → console | `49535343-8841-43F4-A8D4-ECBE34729BB3` | write (avec réponse) |

Connexion : `connectGatt(autoConnect=false, TRANSPORT_LE)`, **pas de négociation MTU** (MTU 23).
Les requêtes de 23 et 27 octets passent donc en *long write* (prepare/execute) automatiquement.
Le SDK attend **1 s** avant chaque activation de notification (contournement d'un firmware).

## 3. Séquence de connexion (protocole Domyos)

1. `discoverServices`
2. écriture du CCCD de `…1E4D` (notifications)
3. `GetEquipmentId` (0xC9) — 2 essais — identifie le modèle
4. infos console : `GetVersion` (0xA3), `GetSerialNumber` (0xA4), `GetUsageHour` (0xA5), `GetCumulativeKM` (0xAB), `GetBluetoothVersion` (0xA6)
5. `SetSessionData(3)` (0xC4) : remise à zéro des cumuls de séance
6. polling `GetInfoValue` (0xAC) **toutes les 300 ms**
7. pendant la séance : `SetHotKey` (start/pause/stop), `SetInfoValue` (résistance), `SetDisplayZone…` (afficheur)

Le « mode » (setting / workout) du SDK est un **état purement côté app** : aucune trame n'est envoyée.

## 4. Format des trames

```
Requête :  F0  op        payload…  cs
Réponse :  F0  op+0x10   payload…  cs
cs = (somme de tous les octets précédents) & 0xFF
```
- Entiers multi-octets en **big-endian**.
- Dans les commandes d'écriture, `FF` / `FFFF` = « ne pas modifier ».
- Une seule commande en vol ; timeout **2,2 s** par tentative ; renvois selon la commande.
- Les réponses arrivent en notifications de 20 octets max : il faut chercher `F0 <op+0x10>` puis
  accumuler jusqu'à la longueur attendue et vérifier le checksum.

## 5. Commandes

| Op | Rép. | Nom | Payload requête | Long. réponse | Renvois |
|---|---|---|---|---|---|
| `C9` | `D9` | GetEquipmentId | — | 8 | 0 |
| `AC` | `BC` | GetInfoValue | — | 26 | 0 |
| `AD` | `BD` | SetInfoValue | 20 octets (§7) | 23 (écho) | 1 |
| `C8` | `D8` | SetHotKey | 1 : 0 pause, 1 start, 2 stop | 4 | 4 |
| `C4` | `D4` | SetSessionData | 1 : 3 = effacer la séance | 4 | 0 |
| `CA` | `DA` | SetFanSpeedLevel | 1 : 0..5 | 4 | 0 |
| `AF` | `BF` | SetBeep | 1 : 0/1 | 4 | 0 |
| `CB` | `DB` | SetDisplayZone (zones 1-6) | 6 × 4 octets (§8) | 27 | 1 |
| `CD` | `DD` | SetDisplayZoneSecondArea (7-12) | 6 × 4 octets | 27 | 1 |
| `CC` | `DC` | SetDisplayZoneOff (1-6) | 6 × (01 = éteint, FF = inchangé) | 9 | 0 |
| `CE` | `DE` | SetDisplayZoneOffSecondArea | 6 octets | 9 | 0 |
| `A3` | `B3` | GetVersion | — | 7 → `maj.min.0` | 0 |
| `A4` | `B4` | GetSerialNumber | — | 13 | 0 |
| `A5` | `B5` | GetUsageHour | — | 7 (u16) | 0 |
| `AB` | `BB` | GetCumulativeKM | — | 8 (u16) | 0 |
| `A6` | `B6` | GetBluetoothVersion | — | 4 (u8) | 0 |
| `C7` | `D7` | GetErrorHistoricList | — | 13 (10 codes) | 0 |
| `E0` | `F0` | GetWorkoutResult | 1 : n° utilisateur | 13 | 0 |
| `E1` | `F1` | ResetWorkoutResult | 1 : n° utilisateur | 4 | 0 |
| `A1` | `B1` | SetFTMS | 1 : 0/1 (active le service FTMS) | 4 | 0 |
| `C0` | `D0` | SetOtherInfo (musculation) | 5 × u16 | 13 | 0 |
| `C2` | `D2` | SetRepetition | — | 7 | 0 |
| `E3`/`E4` | `F3`/`F4` | EL Set/Get Incline (elliptique inclinable) | 1 | 4 | 0 |
| `A7` | `B7` | GetTreadmillData (tapis) | — | 16 | 0 |
| `CF` | `DF` | GetTreadmillInclineVoltage | — | 5 | 0 |
| `A2` | `B2` | AskMotorInclineCalibration (tapis) | `01` | 4 | 0 |

**Non implémentées volontairement** (risque de « briquer » la console) : `A8` RebootConsole,
`A0`/`C6` menu SAV, `C5` + trames `55 55 … 04` de mise à jour firmware PIC.

### Exemples
```
GetInfoValue       F0 AC 9C
GetEquipmentId     F0 C9 B9
SetFanSpeed(3)     F0 CA 03 BD
SetHotKey(start)   F0 C8 01 B9
SetSessionData(3)  F0 C4 03 B7
```

### GetEquipmentId
Les 5 octets de payload sont écrits en hexadécimal puis lus comme un **nombre décimal**
(codage BCD) : `00 08 80 68 19` → `8806819` (Training Bike 900). Voir le catalogue dans
`EquipmentCatalog.kt`.

### GetSerialNumber
`String.format("%02d%02d%05d", b2, b3, b4 | b5<<16 | b6<<8)` — l'ordre d'octets curieux est celui de l'original.

### GetWorkoutResult
`user u8, durée u16 (min), distance u16/10 (km), kcal u16, vitesse moy. u16/10, bpm moy. u8`.

## 6. GetInfoValue (0xAC → 26 octets) — vélo / elliptique

Indices **absolus** dans la trame (0 = `F0`, 1 = `BC`) :

| Octets | Champ | Unité |
|---|---|---|
| 2-3 | compteur (tours) | u16 |
| 4-5 | puissance | W |
| 6-7 | vitesse | /10 km/h |
| 8-9 | cadence | rpm |
| 10-11 | kcal séance | kcal |
| 12-13 | distance séance | /10 km |
| 14 | niveau de résistance | 1..32 |
| 15 | code erreur | 0 = OK |
| 16 | « tap on equipment » (mains sur capteurs) | 0/1 |
| 17-18 | fréquence cardiaque | bpm |
| 19-20 | vitesse moyenne séance | /10 km/h |
| 21 | (non utilisé) | |
| 22 | dernier bouton pressé | voir ci-dessous |
| 23 | vitesse ventilateur | 0..5 |
| 24 | état hotkey | 0 pause, 1 start, 2 stop |
| 25 | checksum | |

Boutons vélo : 1-4 généraux, 5 Quit, 6 Start, 7 Program, 8 Load+, 9 Load−, 10 Fan−, 11 Fan+.

Variantes : **rameur** — 6-7 = temps/500 m (s), 8-9 = coups/min. **Tapis** — 2-3 = inclinaison
codée (1..1000 → −x/10 ; >1000 → (x−1000)/10), le reste décalé (voir `DCTreadmillGetInfoValueCommand`).

## 7. SetInfoValue (0xAD, 23 octets)

```
F0 AD  FF FF  [vitesse u16 ×10]  FF FF FF FF  [résistance u8]  FF FF
       [inclinaison u16]  [watts u16]  [LED cardio u8]  [LED BT u8]  FF FF FF  cs
```
- vitesse : 0..254 (0..25,4 km/h) ; résistance : 1..32 ; watts : 0..1000
- inclinaison : 0 → 0 ; +x % → 1000 + x×10 ; −x % → x×10
- LED cardio : 0 off, 1 bleu, 2 vert, 3 jaune, 4 orange, 5 rouge (0..7 acceptés)
- tout champ à `FF`/`FFFF` est ignoré par la console.

## 8. Afficheur (0xCB / 0xCD)

Chaque zone = `type, var1 (u16 BE), var2` :

| type | Signification | var1 | var2 |
|---|---|---|---|
| 1 | nombre | valeur × 10^déc | nb décimales (0, 1, 2 ; zone 1 : 0 ou 2) |
| 1 | durée (zones 2-12) | min×100 + s | 0 |
| 2 | texte prédéfini | index de chaîne firmware | FF |
| 3 | horloge (zone 1) | min×256 + s | FF |
| FF | inchangé | FFFF | FF |

Mapping vélo de l'app : 1 = principal, 2 = vitesse, 3 = cardio, 4 = cadence, 5 = kcal (modulo 1000),
6 = résistance, 7 = distance.

## 9. FTMS — particularités Domyos

Standard FTMS 1.0 (Indoor Bike Data `0x2AD2`, Control Point `0x2AD9` en indication,
Machine Status `0x2ADA`, plages `0x2AD4…0x2AD8`, Device Information `0x2A24` = ID modèle).

- `Request Control` (`00`) avant toute commande ; redemandé si « control not permitted ».
- Résistance cible : `04 <u8, résolution 0,1>`.
- Pour les ID **8607949, 8862828, 8806819** (Challenge Bike, Training Bike 900) le niveau est
  multiplié par l'incrément de « Supported Resistance Level Range » avant envoi.
- Puissance cible : `05 <s16 LE>` ; start `07` ; pause `08 02` ; stop `08 01`.

## 10. Points non vérifiés sur matériel

- sémantique exacte de « dernier bouton pressé » (valeur maintenue ou impulsion) ;
- table des index de textes de l'afficheur (dans le firmware) ;
- nécessité réelle du délai de 1 s avant les notifications sur votre console.

Utilisez `tools/domyos_cli.py run <adresse> --raw` pour vérifier ces points sur votre vélo.
