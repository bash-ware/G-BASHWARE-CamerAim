# G-BASHWARE CamerAim — plan integrisanog sistema

Datum: 2026-09-08

Status: realizacija je započeta 2026-09-09 po korisnikovom odobrenju.

## Trenutni napredak

1. **Faza 1 završena:** pregledan je postojeći plugin i definisan API v1 u
   folderu shared, sa primerima payload-a i pravilima kompatibilnosti.
2. **Faza 2 implementirana, test uređaja čeka:** server, SQLite skladište,
   simulator i Android/Compose foreground-service prototip su u istom
   repozitorijumu. Server/simulator end-to-end testovi, Android unit testovi,
   lint i debug APK build prolaze.
3. **Faza 3 nije zaključena:** nema zabeleženog testa na fizičkom telefonu ili
   emulatoru. Rezultati se upisuju u docs/ANDROID-DEVICE-TESTS.md pre odluke o
   održivosti direktnog background praćenja.
4. Integracija slanja iz UGS plugina namerno nije započeta pre odluke iz faze 3.

Postojeći lokalni diff u windows-camera.ps1 (eksperimentalno otkrivanje i
otvaranje dodatnih Windows camera stream formata) nije menjan niti je deo novog
izdanja.

## Cilj i obim prve verzije

Jedan projekat i GitHub repozitorijum obuhvataju UGS plugin, lokalni server i Android aplikaciju. Plugin periodično šalje fotografije i podatke o realizaciji posla. Server čuva podatke i prosleđuje ih Android aplikaciji, koja prikazuje dashboard i lokalna obaveštenja.

Prva verzija radi bez ntfy-ja i Firebase-a. Android aplikacija služi za nadzor, bez komandi za pokretanje ili pomeranje mašine. Pristup je preko lokalne mreže; pristup van nje može se kasnije omogućiti VPN-om.

## 1. Struktura projekta

```text
ugs-camera-monitor/    Postojeći UGS plugin
server/                Lokalni server
android-app/           Android aplikacija
shared/                API specifikacija i primeri podataka
installer/             Windows instalacija
docs/                  Dokumentacija
```

Komponente imaju sopstvene verzije i deklarisanu kompatibilnost zajedničkog API-ja. Postojeće nedovršene izmene kamere treba pregledati odvojeno pre integracije.

## 2. Zajednički podaci i pravila

Definisati verzionisani API ugovor pre razvoja komponenti:

- Identifikator mašine i jedinstveni identifikator svakog pokretanja posla.
- Naziv G-code fajla, stanje posla, napredak, proteklo i procenjeno preostalo vreme.
- Feed, obrtaji vretena i XYZ, samo kada ih UGS ili kontroler stvarno prijavljuje.
- Jedinice, koordinatni sistem i razlika između zadatih i prijavljenih vrednosti.
- Vreme nastanka slike i poslednjeg primljenog statusa.
- Događaji: početak, pauza, nastavak, završetak, otkazivanje, alarm i gubitak veze.
- Identifikatori i redosled događaja za ponovno slanje i sprečavanje dupliranja.

Nepoznate vrednosti prikazuju se kao „—“. Procena preostalog vremena mora biti označena kao procena. Slanje poslednje G-code komande samo po sebi ne znači da je završeno kretanje mašine: proveriti semantiku UGS događaja i potvrdu završetka kontrolera. Prekid veze nije uspešan završetak. Završetak programa ne garantuje fizički kvalitet obrade.

## 3. Lokalni server

Predlog tehnologije: ASP.NET Core na podržanoj LTS verziji .NET-a, SQLite i lokalni folder za fotografije. Tačnu verziju SDK-a odabrati na početku realizacije.

Server treba da:

- Prima telemetriju, događaje i JPEG slike od plugina.
- Čuva istoriju poslova i događaja.
- Omogući trenutno stanje preko REST API-ja i promene preko WebSocket veze.
- Omogući preuzimanje propuštenih događaja posle ponovnog povezivanja.
- Razlikuje nedostupan plugin, zastarele podatke i nedostupnu kameru.
- Ograniči zauzeće diska fotografijama i logovima kroz podesivu politiku čuvanja.
- Ima pristupne tokene i odvojene dozvole za slanje i čitanje podataka.
- Omogući povezivanje telefona QR kodom; tajne ne zapisivati u logove ili repozitorijum.

U razvoju se pokreće ručno, a završna Windows instalacija omogućava rad kao servis. Predviđeno je samostalno izdanje koje ne zahteva od korisnika instaliranje razvojnog SDK-a.

Plugin na istom računaru koristi localhost. Telefon koristi mrežnu adresu servera, jer localhost na telefonu označava sam telefon. Dokumentovati mrežno vezivanje, port, autentifikaciju i zaštitu prenosa.

## 4. Slanje iz UGS plugina

Dodati podešavanja adrese servera, tokena i intervala:

- Telemetrija: početno svake dve sekunde.
- Slika: početno svakih deset sekundi, uz mogućnost promene intervala i kvaliteta.
- Važni događaji: odmah.
- Završna fotografija: kada je kamera dostupna.

Koristiti postojeći tok kamere, bez ponovnog otvaranja uređaja. Mrežne operacije i priprema slike rade u pozadini, bez blokiranja UGS interfejsa ili komunikacije sa mašinom. Stare slike se preskaču i zadržava se najnovija. Važni događaji privremeno se čuvaju u ograničenom redu i ponovo šalju bez dupliranja. Definisati ponašanje pri restartu plugina i iscrpljenju reda.

## 5. Android dashboard

Predlog tehnologije: Kotlin i Jetpack Compose, sa G-BASHWARE CamerAim brendingom.

Ekrani i funkcije:

- Povezivanje sa serverom ručnim unosom ili QR kodom.
- Slika kamere sa jasno prikazanom starošću slike.
- Status mašine, naziv posla i napredak.
- Proteklo i procenjeno preostalo vreme, feed, RPM i XYZ.
- Istorija poslova, događaji i završne fotografije.
- Podešavanje veze i dugme „Pokreni/Zaustavi praćenje“.

Kada dashboard nije otvoren, aplikacija prima status i događaje; slike preuzima pri otvaranju ili po potrebi. Zastarela slika ne sme izgledati kao trenutni prikaz uživo.

## 6. Praćenje u pozadini bez ntfy-ja

Ovu funkciju proveriti rano, pre detaljnog dizajna dashboarda:

- Korisnik pokreće praćenje iz aplikacije.
- Foreground servis prikazuje stalnu notifikaciju sa stanjem veze.
- Aplikacija lokalno generiše obaveštenja o završetku, alarmu i prekidu veze.
- Ponovno povezivanje preuzima propuštene događaje bez ponavljanja već prikazanih obaveštenja.
- Ako Android zaustavi praćenje, pri sledećem otvaranju jasno prikazati prekid i uskladiti stanje sa serverom.
- Obraditi dozvolu za notifikacije, korisničko zaustavljanje, sistemske timeout-e i gubitak mreže.

### Ograničenja koja treba proveriti

Foreground servis ne garantuje neograničen rad niti automatski restart. Na Androidu 15 i novijem, za aplikacije koje ciljaju taj API ili noviji, dataSync foreground servis ima ograničenje rada u pozadini od šest sati tokom 24 sata, uz pravila resetovanja koja Android dokumentuje. Pokretanje tog tipa servisa iz BOOT_COMPLETED nije dozvoljeno.

Tip servisa odabrati prema stvarnoj funkciji aplikacije i važećim pravilima, bez korišćenja pogrešnog tipa radi zaobilaženja ograničenja. Proveriti odgovarajuće mogućnosti za povezani uređaj/companion pristup, ako funkcija i uslovi to opravdavaju. Ne obećavati neprekidan rad, automatski oporavak ili trenutna obaveštenja dok nisu testirani na korisnikovom telefonu.

Ako direktno praćenje u pozadini ne zadovolji zahteve, rezultate predstaviti korisniku pre odluke o eventualnom dodavanju push servisa. ntfy i Firebase nisu deo prve verzije.

Izvori:

- [Android foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout?hl=en)
- [Promene za aplikacije koje ciljaju Android 15](https://developer.android.com/about/versions/15/behavior-changes-15)

## 7. Testiranje i kriterijumi prihvatanja

Prvo koristiti simulator posla, zatim stvarni UGS:

- Završetak, pauza, nastavak, otkazivanje i alarm prikazuju se kao različiti događaji.
- Prekid veze nikada ne postaje uspešan završetak posla.
- Restart servera, gubitak Wi-Fi-ja i ponovno povezivanje ne dupliraju događaje; istorija se nadoknađuje.
- Vreme poslednjeg statusa i fotografije jasno pokazuje zastarelost.
- Nedostupan server i spora mreža ne ometaju rad UGS-a.
- Proveriti zaključan ekran, štednju baterije, uklanjanje aplikacije iz Recent apps, Force stop i restart telefona.
- Izvesti višesatni test, uključujući posao duži od šest sati.
- Meriti potrošnju baterije, CPU-a, mreže i diska; rezultate koristiti za izbor intervala.
- Proveriti autentifikaciju, ograničenja veličine poruka/slika i oporavak posle neispravnih podataka.

## 8. Build, instalacija i isporuka

- NBM za UGS.
- Windows server paket i instalacija servisa.
- Potpisani Android APK.
- Uputstvo za povezivanje, praćenje, nadogradnju i dijagnostiku.
- Automatski build i testovi kroz GitHub Actions, sa verzijama i checksum fajlovima.

Ključ za potpisivanje APK-a čuvati van Git repozitorijuma i obezbediti njegov backup. Za nadogradnje APK-a zadržati isti identitet aplikacije i potpis.

## Redosled realizacije

1. Pregled postojećeg koda i dogovor API ugovora.
2. Mali server, simulator i Android prototip za proveru rada u pozadini.
3. Odluka o održivom načinu praćenja na osnovu testa telefona.
4. Integracija telemetrije, događaja i fotografija u UGS plugin.
5. Dashboard, istorija i povezivanje telefona.
6. Windows servis, pakovanje, CI i potpisivanje APK-a.
7. Kompletni testovi na CNC računaru i telefonu.

## Priprema okruženja

Korisnik priprema Android SDK, po mogućnosti kroz Android Studio, uz Platform-Tools i Build-Tools. Za fizički telefon uključiti USB debugging. Pri početku realizacije proveriti stvarno instalirane JDK, Android SDK i .NET SDK verzije i uskladiti build alate.

Implementaciju započeti tek po korisnikovoj narednoj instrukciji.
