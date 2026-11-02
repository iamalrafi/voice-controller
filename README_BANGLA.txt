VOICE CONTROL - offline voice diye phone control
=================================================

Eta ki?
  Tumi bolo "open whatsapp and select Tanvir and tap type box and type hello and send"
  phone nijei sob kore dibe. Internet lage na, AI chatbot na. Shudhu command bujhe kaj kore.

APK banano (tomar PC-te kichu install korte hobe na, GitHub free-te bonaye dey)
-----------------------------------------------------------------------------
1. github.com e giye free account kholo (thakle login koro).
2. Upore daane "+" -> "New repository". Name: VoiceControl. "Create repository" chapo.
3. Ei zip-ta unzip koro. Repository page-e "uploading an existing file" link-e chapo.
   Unzip kora VoiceControl folder-er BHETORER sob file/folder (.github folder shoho) drag kore felo.
   Niche "Commit changes" chapo.
   (.github folder upload na hole: "Add file" -> "Create new file", name-e likho
    .github/workflows/build.yml  ar build.yml-er lekha copy-paste koro.)
4. Upor-er "Actions" tab-e jao. "Build APK" cholche. 5-10 minute opekkha koro, sobuj tick ashle:
   oi run-e chapo -> niche "Artifacts" -> "VoiceControl-APK" download koro -> unzip -> app-debug.apk
   (Lal X ashle: oi run khule error-er lekha amake pathao, ami thik kore dibo.)
5. app-debug.apk phone-e pathao, chapo, "Install unknown apps" allow koro, install koro.

Prothombar chalano
------------------
1. App kholo. "Step 1" er 3-ta jinish allow koro (sob ✅ hote hobe):
   - Microphone
   - Display over other apps
   - Accessibility service  (list-e "Voice Control" khuje ON koro)
   Accessibility grey hoye thakle: app-er "Accessibility is greyed out? Tap here" button chapo ->
   App info page-e uporer daane ⋮ -> "Allow restricted settings" -> fire giye abar ON koro.
2. "Start voice control" chapo. Phone-er screen-e choto 🎤 bubble ashbe.
3. Ja ichha bolo. Prothome nijer text box-e type kore test-o korte paro ("Test without speaking").
4. Bubble-e tap korle mute/unmute. Bondho korte app-e Stop ba notification-er Stop.

Ki ki bolte paro (English-e)
----------------------------
  open / launch / start <app>          open the youtube,  launch chrome,  open browser
  tap / click / select / press <text>  select Tanvir,  tap send,  click on camera
  tap type box                         (text box-e cursor ney)
  type / write <text>                  type hello how are you comma wait for me
  send                                 Send button chape  (send hello = type + send)
  search for <text>                    search for funny cats
  scroll down / scroll up, go back, go home, recent apps, show notifications,
  take a screenshot, volume up / volume down, lock screen, wait three seconds
  Joraiya: "and", "then" diye joro. Je kono shobdo-e bolte paro, fixed sentence lage na.

Sidhanto
--------
  * "type" er por ja bolbe sheta text hishebe type hoy, "and send"/"and open ..." dhorle notun command.
  * Voice ekhon English. Banglay bolar jonno Bangla offline model lagbe (porer dhap).
  * Always-listening hole battery kichu beshi khay. Dorkar na hole mute/stop koro.
  * Bank-er mto secure screen-e accessibility kaj kore na (Android-er nijer niyom).
  * Kono app update hole tar button-er naam bodlate pare; tokhon "tap" step fail korte pare.

Shabdo bartano (developer-der jonno na, tomar jonno)
-----------------------------------------------------
  Notun shobdo shikhate chaile CommandParser.kt-er upore shobdo-er list-e just shobdo jog koro
  (jemon OPEN-er list-e "kholo"). Ami (Claude) kore dite pari, bolo.
