# Berserk Manga Downloader & Android Reader App

Progetto completo per scaricare tutti i capitoli di **Berserk** suddivisi per volumi da OnePiecePower e leggerli comodamente tramite un'app Android nativa moderna sviluppata in **Kotlin** e **Jetpack Compose**.

---

## 📁 Struttura della Cartella

```
berserk_reader/
├── Berserk_Manga/              # Cartella con tutti i volumi e capitoli scaricati
│   ├── Volume 01/
│   │   ├── Capitolo 000-01 - Il guerriero nero/
│   │   │   ├── 01.jpg
│   │   │   ├── 02.jpg
│   │   │   └── ...
│   │   ├── Capitolo 000-02 - Il marchio/
│   │   └── Capitolo 000-03 - Gli angeli custodi dei desideri 1/
│   ├── Volume 02/
│   └── ...
├── download_berserk.py          # Script Python per scaricare volumi e capitoli
├── export_to_cbz.py             # Script per convertire i volumi in formato .CBZ
├── full_catalog.json           # Indice completo di tutti i 44 volumi e 404 capitoli
└── BerserkReaderApp/            # Progetto Android completo in Kotlin + Jetpack Compose
    ├── app/
    │   ├── src/main/java/com/berserk/reader/
    │   │   ├── MainActivity.kt
    │   │   ├── navigation/AppNavigation.kt
    │   │   ├── model/MangaModels.kt
    │   │   ├── data/MangaRepository.kt & PreferencesManager.kt
    │   │   └── ui/screens/ (Home, VolumeDetail, Reader)
    │   └── src/main/assets/berserk_catalog.json
    └── build.gradle.kts
```

---

## 🚀 1. Downloader Python (`download_berserk.py`)

Lo script gestisce il download automatico, la sanitizzazione dei nomi per Windows e Android, e la ripresa automatica dei download interrotti (evita di riscaricare le pagine già presenti).

### Comandi utili:

- **Scaricare tutti i 44 volumi:**
  ```powershell
  python download_berserk.py --all
  ```

- **Scaricare volumi specifici (es. Volumi da 1 a 5):**
  ```powershell
  python download_berserk.py --volume 1-5
  ```

- **Scaricare un singolo volume (es. Volume 10):**
  ```powershell
  python download_berserk.py --volume 10
  ```

- **Specificare una cartella di destinazione personalizzata:**
  ```powershell
  python download_berserk.py --all --output "D:/Manga/Berserk"
  ```

---

## 📦 2. Esportatore CBZ (`export_to_cbz.py`)

Se desideri avere i volumi anche in formato `.cbz` (compatibile con Tachiyomi, Mihon, Kuro Reader, Perfect Viewer, CDisplayEx, ecc.):

```powershell
# Esporta tutti i volumi scaricati in file .cbz
python export_to_cbz.py

# Esporta solo il volume 1
python export_to_cbz.py --volume 1
```

---

## 📱 3. App Android (`BerserkReaderApp`)

Un'applicazione Android moderna a tema scuro (ispirata all'estetica dark fantasy di Berserk) con accenti rosso cremisi, sviluppata con:
- **Kotlin** & **Jetpack Compose**
- **Material 3**
- **Navigation Compose**
- **Coil** per caricamento immagini ad alte prestazioni con cache disco (512 MB) e memoria
- **Zoom & Pan a due dita (Pinch-to-zoom)** e doppio tocco per esaminare le tavole dettagliate di Kentaro Miura.

### ✨ Funzionalità dell'App:

1. **Doppia Modalità (Offline & Online):**
   - **Offline:** Puoi copiare la cartella `Berserk_Manga` sullo smartphone (es. in `/sdcard/Download/Berserk_Manga`) e selezionarla nell'app: leggerà tutte le pagine istantaneamente da memoria locale senza connessione internet!
   - **Online:** Anche se non hai ancora trasferito tutti i file sul telefono, l'app include un fallback automatico che riproduce e scarica le scan in streaming direttamente da CDN!
2. **Schermata Home:**
   - Banner **"Continua a Leggere"** per riprendere subito l'ultimo capitolo e pagina esatta.
   - Griglia di tutti i **44 Volumi** con copertina, conteggio capitoli e indicatore di progresso.
3. **Schermata Dettaglio Volume:**
   - Lista completa dei capitoli con titolo, badge di lettura (segno di spunta verde per capitoli già letti) e pulsante rapido per iniziare.
4. **Lettore Manga Avanzato:**
   - **Due modalità di lettura:**
     - **Scroll Verticale Continuo:** stile Webtoon / infinite scroll, ideale per smartphone.
     - **Paginato Orizzontale:** sfoglia pagina per pagina con zoom fluido.
   - **Interfaccia immersiva a scomparsa:** tocca il centro dello schermo per mostrare/nascondere la barra superiore e inferiore con cursore pagine e pulsanti Capitolo Precedente/Successivo.
   - **Salvataggio automatico:** memorizza l'ultimo capitolo e pagina letta.

### Come compilare o installare l'App:
- **Aprire in Android Studio:**
  Basta aprire la cartella `BerserkReaderApp` con Android Studio e cliccare su **Run** o **Build > Build APK**.
- **Da linea di comando con Gradle:**
  ```powershell
  cd BerserkReaderApp
  .\gradlew assembleDebug
  ```
  L'APK generato sarà pronto in `app/build/outputs/apk/debug/app-debug.apk`.
