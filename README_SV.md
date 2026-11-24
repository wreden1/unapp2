# UnApp – första Android-prototypen

Detta är startprojektet för UnApp, en Android-appstudio med målet att skapa appar med lokal AI.

## Vad som finns i denna version
- Native Android-projekt i Kotlin och Jetpack Compose.
- Svensk startsida med appbeskrivning.
- Grundläggande ytor för Mina appar, Förhandsvisa, Kod & design och APK-bygge.
- Android `compileSdk 36` och `targetSdk 36`.

## Vad som INTE är färdigt ännu
- Ingen AI-modell är inbyggd.
- Knappen "Skapa app med AI" är ännu inte kopplad till en generator.
- Ingen APK-kompilering/signering av användarprojekt finns ännu.
- Detta är källkod, inte en installerbar APK.

## Nästa utvecklingssteg
1. Verifiera bygget på Galaxy S22 Ultra.
2. Integrera en lokal LLM via LiteRT-LM eller en annan lämplig Android-runtime.
3. Låt AI:n skapa ett strukturerat app-projekt (skärmplan, datamodell, funktioner och kod).
4. Lägg till förhandsvisning och projektredigering.
5. Bygg och signera APK lokalt på telefonen.

## Byggmiljö
Öppna projektet i en Android-utvecklingsmiljö som stöder moderna Android Gradle Plugin-projekt. Projektet är konfigurerat för Android 16 SDK (API 36).


## APK-bygge från mobilen via GitHub Actions
Projektet innehåller nu `.github/workflows/android-apk.yml`. Ladda upp hela projektmappen till ett GitHub-repository. Öppna sedan fliken **Actions**, välj **Build UnApp APK** och tryck **Run workflow**. När bygget lyckas öppnar du körningen och laddar ner artefakten **UnApp-debug-apk**. Den innehåller `app-debug.apk`.

Detta skapar en debug-signerad APK för testinstallation. Det är ännu inte en färdig produktionsversion och AI-/APK-genereringsfunktionerna är inte implementerade.


## Bygg APK utan Termux (GitHub Actions)
Projektet innehåller nu `.github/workflows/build-apk.yml`.
1. Ladda upp ZIP-projektet till ett nytt GitHub-repository från webbläsaren.
2. Packa upp filerna i repositoryt och se till att mappen `.github/workflows` följer med.
3. Öppna fliken Actions och kör workflowen `Build UnApp APK` (eller vänta på att push-bygget startar).
4. När bygget är klart: öppna körningen, välj Artifacts och ladda ner `UnApp-debug-apk`.
5. Packa upp artefakten och installera `app-debug.apk` på Android.

Detta skapar en debug-signerad APK för testinstallation. Det gör inte AI-motorn färdig.
