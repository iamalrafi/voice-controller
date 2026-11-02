# Voice Control - kibhabe app banabe ar chalabe (zero theke)

Ei app ta tomar voice shune phone control kore. Kono AI chatbot na, kono internet lagbe na (chalate). Tumi shudhu command dibe, o shudhu seta kore.

## Ki ki lagbe
- Ekta computer (Windows / Mac / Linux), internet, ~10 GB khali jayga (Android Studio-r jonno)
- Tomar Android phone (Android 7 ba tar upore) + USB cable

---

## Step 1: Android Studio install koro (ekbar-i)
1. https://developer.android.com/studio e jao, Download koro, install koro (shob jaygay Next / Next).
2. Prothom bar khulle "Standard" setup choose koro. Ota nijer theke kichu GB download korbe, kichu minute lagbe.

## Step 2: Project kholo
1. Ei zip ta unzip koro (jemon Desktop-e `VoiceControl` folder).
2. Android Studio -> **Open** -> `VoiceControl` folder select koro -> OK. "Trust project" bolle **Trust**.
3. Niche "Gradle sync" cholbe. Prothom bar 5-15 minute lagte pare (internet lagbe, shob library nijer theke namabe). Sesh hole niche error thakbe na.
   - Jodi "Update Gradle / Android Gradle Plugin?" pop-up ashe, ekhon **Don't remind me** / **Later** chap. Na hole je error ashe seta copy kore amake pathao.

## Step 3: Phone ready koro
1. Phone Settings -> **About phone** -> **Build number**-e 7 bar tap koro ("You are a developer" ashbe).
2. Settings -> **Developer options** -> **USB debugging** ON koro.
3. USB diye computer-e lagao. Phone-e "Allow USB debugging?" ashle **Allow**.

## Step 4: App phone-e chalao
1. Android Studio-r upore phone-er naam dekhabe. Pashe sobuj **Run (triangle)** button chap.
2. App phone-e install hoye nijei khulbe.

> Aro sohoj upay (cable chara): menu **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**. Sesh hole "locate" link-e click koro, `app-debug.apk` file ta phone-e pathao ar install koro.

## Step 5: App-e 3ta jinish allow koro (ekbar)
App khulle upore 3ta button dekhbe. Ekta ekta kore chap:
1. **Allow microphone**
2. **Allow display over other apps**: "Voice Control" khuje ON koro.
3. **Turn on the Voice Control service**: list-e "Voice Control" khuje ON koro.
   - Switch grey hoye thakle ("Restricted setting"): app-er niche "Open App info" button chap -> opore dan kone **⋮** -> **Allow restricted settings** -> abar step 3.

Tin ta-i ✅ hole sobuj mic button (🎤) tomar screen-e vasbe.

## Step 6: Babohar
1. 🎤 button-e **tap** koro (red hobe 👂, mane shunche).
2. Tomar command bolo, tarpor **ektu thamo** (~2 second). Hold korbe, tarpor nijei kaj korbe (button amber ⏳ hobe).
3. Button ke **drag** kore jekhane khushi rakhte paro.

Aage app-er vitore "Try it by typing" box-e lekhe test korte paro ("Show what it understood" chaple dekhabe o ki bujhlo).

### Command-er udahoron
| Tumi bolo | Ki hobe |
|---|---|
| open youtube / open the youtube app / please open youtube | YouTube khulbe |
| open browser and open claude | prothome browser, tarpor Claude |
| open whatsapp and select Tanvir and tap type box then type hello and send | WhatsApp -> Tanvir chat -> message box -> "Hello" lekhe -> Send |
| scroll down / scroll up / swipe left | scroll / swipe |
| go back / go home / open recent apps / open notifications | phone-er button gulo |
| wait 3 seconds then open camera | 3 second theke camera |
| youtube kholo and whatsapp kholo | Banglish-o chole |

**Dhorbe na:** "what is Bangladesh" ba "who is Bill Gates" - eta shudhu command bhuje, kono jnan nei.

### Ekta joruri niyom
"type" er por tumi ja bolbe sheta **hubohu likhe dibe**. Tai text sesh hole **"and" / "then"** diye alada koro:
- ✅ "type hello how are you **and send**"
- ❌ "type hello how are you send" (tahole "send" kothatao text hoye jabe)

---

## Offline voice model (Vosk) - optional, kintu "puro offline" er jonno
Na korleo app cholbe: tokhon phone-er nijer recognizer bebohar kore (Google-er; offline pack install thakle offline-e kaj kore, na thakle internet lage).

Puro offline chaile:
1. https://alphacephei.com/vosk/models theke **vosk-model-small-en-us-0.15** (~40 MB) download koro, unzip koro.
2. Unzip kora folder-er **bhitorer shob kichu** (`am`, `conf`, `graph`, `ivector` ...) copy kore `app/src/main/assets/model-en-us/` e rakho.
3. Abar Run koro. App nijei model dhorbe (app-er "How it listens" e ✅ dekhabe).
4. Prothom bar mic tap korle model load hote kichu second lagbe.

Phone recognizer-er offline pack: Google app -> Settings -> Voice -> Offline speech recognition e English (ar Bengali) download koro. (Naam phone vede alada hote pare.) Bangla command dite chaile app-e language "bn-BD" kore dao.

---

## Sima / jene rakho
- **Banking app, password screen, kichu secure screen** Android nijei block kore. Okhane tap/type korbe na. Eta Android-er suroksha niyom.
- App-er UI update hole button-er naam bodle jete pare. App nam/text khuje kaj kore (fixed jayga na), tai beshirbhag somoy thik thake.
- Vosk-er chhoto model-e **manusher naam** (jemon "Tanvir") thik chine na pare. App ta shunano naam-er shathe screen-er naam *kachakachi* mile dekhe (tanvir / tan veer / tanbir sob chole), tobe 100% na.
- Command beshi lomba hole majhe kono step fail korle ota **thame jabe** (bhul jaygay type na hoy) ar App-er "Log"-e karon likhe dibe.
- Mic button shobsomoy screen-e thake bole ektu battery khay, kintu bar bar mic on rakhe na (tap korle tabei shune).

## Kichu kaj na korle
- **Gradle sync fail**: error ta copy kore amake pathao.
- **Mic button dekha jacche na**: Step 5-er 2 ar 3 number ✅ ache kina dekho.
- **Tap korle kichu hoy na / "didn't hear anything"**: microphone allow ache kina dekho; Vosk model dile app-er "Engine" chap kore **Vosk** kore dekho.
- Je error/screenshot dekhabe seta amake pathaio, ami thik kore dibo.

## Code kothay (kauke dekhate chaile)
- `CommandParser.kt`: tomar kotha bhenge command banay (open/tap/type/send...)
- `Executor.kt`: asol kaj: app khola, tap, type, scroll
- `VoiceControlService.kt`: sobuj mic button + shuna + chalano
- `SpeechEngines.kt`: Vosk ar phone-er recognizer
- `Fuzzy.kt`: "whats app" = WhatsApp er moto *kachakachi mil* dhorar jonno
