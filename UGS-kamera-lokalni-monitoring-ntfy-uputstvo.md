# Uputstvo za implementaciju: UGS kamera, lokalni monitoring i ntfy

## 1. Zadatak za agenta

Implementiraj dodatak za **UGS Platform (Universal Gcode Sender)** koji objedinjuje:

1. Kameru za preciznije pozicioniranje alata, sa nišanom i kalibracijom pomeraja kamera–alat.
2. Lokalni web dashboard za praćenje posla sa telefona preko kućne mreže.
3. ntfy obaveštenja za važne događaje.

Ovaj dokument je specifikacija i predlog arhitekture. Dodatak još nije napravljen niti testiran. Prvo proveri stvarnu verziju UGS-a, njegov izvorni kod i dostupne podatke kontrolera. Nazivi predloženih ruta i internih komponenti nisu postojeći UGS API.

Korisnik želi funkcionalnu implementaciju, sa instalacionim paketom, uputstvom i dokazima provere. Ne završavaj samo konceptom ili demonstracijom interfejsa ako je implementacija moguća u dostupnom okruženju. Ako hardver nije dostupan, jasno odvoji softverski proverene delove od provere koja ostaje na mašini.

## 2. Kontekst i dogovoreni smer

- Dodatak je nezavisan od modela CNC kontrolera. Model ploče i firmware nisu preduslov za razvoj; status i komande koriste se kroz UGS API.
- UGS radi na Windows računaru i komunicira sa kontrolerom.
- Korisnik trenutno koristi laser sa poznatim pomerajem u odnosu na alat, ali centriranje laserske tačke procenjuje okom.
- Kamera treba da olakša centriranje stvarne rupe, oznake ili PCB pada. Ne obećavati savršenu tačnost niti određenu toleranciju pre merenja.
- USB kamera se priključuje **direktno na PC**. Predviđen je krut nosač pored vretena.
- Glavni način praćenja je telefon na istoj kućnoj mreži.
- **Cloudflare i javni backend nisu potrebni za prvu verziju.** Korisnik ima Cloudflare Free, ali je izabran jednostavniji lokalni pristup.
- Lokalni server može biti ugrađen u dodatak i raditi na istom računaru kao UGS.
- Za važne notifikacije koristi se **ntfy**. Posebna mobilna aplikacija nije potrebna za MVP: browser za dashboard, ntfy aplikacija za obaveštenja.
- Dodatak i monitoring moraju raditi tokom celog posla.
- Android/iPhone, verzija UGS-a, radna visina kamere i potrebna tolerancija još nisu potvrđeni. Nemoj pretpostaviti da su poznati.

## 3. Kamera koju korisnik razmatra

Listing: https://www.aliexpress.com/item/1005005615520367.html

Korisnik je preneo specifikacije sa više varijanti u istom oglasu:

- USB 2.0, UVC, MJPEG; kod pojedinih varijanti i YUYV.
- 2 MP varijanta: 1920 × 1080 pri 30 fps u MJPEG režimu.
- 8 MP varijanta: navodno Sony IMX179, 3264 × 2448 pri 15 fps, 1920 × 1080 pri 30 fps.
- Rolling shutter; pločica približno 15 × 15 mm.
- U oglasu se pominju objektivi 3,6 i 3,7 mm, približno 90° vidnog ugla. Ranije poređenje korisnika bilo je 2,8 naspram 3,6 mm.
- Specifikacije veličine senzora, površine slike i rezolucije nisu međusobno dosledne.
- **Minimalna udaljenost fokusiranja i mogućnost ručnog podešavanja/zaključavanja fokusa nisu potvrđeni.**

Ne kodirati zavisnost od ovog modela. Podržati standardne UVC kamere i stvarno prijavljene režime. Identifikovati izabranu varijantu pre procene optike. Rezolucija senzora i digitalni zoom nisu dokaz tačnosti centriranja.

## 4. Predložena arhitektura

```text
USB kamera ───────────────► UGS dodatak na Windows PC-u
                                  │
UGS status i komande ◄────────────┤
                                  ├── lokalni HTTP server ──► browser telefona
                                  │                          status + slike
                                  └── HTTPS objava ─────────► ntfy ──► telefon
```

Implementirati kao UGS Platform/NetBeans modul kompatibilan sa ciljnom verzijom. Koristiti postojeće UGS servise i događaje; ne otvarati drugu serijsku vezu prema kontroleru.

Predloženo razdvajanje odgovornosti:

- Prikupljanje UGS statusa i životnog ciklusa posla.
- Snimanje kamere i prikaz nišana.
- Kalibracija i lokalna komanda pomeranja.
- Lokalni server i mobilni dashboard.
- ntfy slanje, filtriranje događaja i ograničeni ponovni pokušaji.
- Čuvanje konfiguracije i kratke istorije događaja.

Ovo mogu biti interne komponente jednog dodatka; nema potrebe za više instalacionih dodataka ili posebnim cloud servisima.

## 5. Kamera i lokalni UGS panel

Obavezno omogućiti:

- Izbor kamere, rezolucije i dostupnog formata/brzine slike.
- Start/stop prikaza, status kamere i razumljivu grešku kada je kamera zauzeta ili odspojena.
- Tanak podesiv nišan; podešavanje boje i debljine, zoom i zamrzavanje slike.
- Jasnu oznaku zamrznute slike i vremena snimanja.
- Rotaciju/ogledanje samo uz pravilno mapiranje koordinata i proveru kalibracije.
- Čuvanje izabrane kamere i podešavanja prikaza.
- Opcione kontrole ekspozicije, fokusa i gain-a samo ako ih konkretan uređaj podržava.

Za MVP je dovoljna jedna kamera. Kamera za centriranje može imati uzak kadar koji ne prikazuje celu obradu; druga pregledna kamera je moguća kasnija nadogradnja.

Automatsko prepoznavanje centara kružnica, PCB fiducijala, perspektivna korekcija i klik-na-sliku za pomeranje nisu obavezni u prvoj verziji. Ne predstavljati ih kao implementirane ako postoji samo ručno centriranje.

## 6. Kalibracija kamera–alat

### Osnovni postupak

1. Korisnik napravi malu referentnu rupu/oznaku alatom na probnom komadu i zabeleži položaj vretena.
2. Korisnik podigne alat na odgovarajuću visinu i postavi kameru na definisanu udaljenost od površine.
3. Ručnim jog komandama centrira istu oznaku pod nišan kamere.
4. Dodatak zabeleži drugu poziciju i izračuna XY pomeraj.
5. Ponoviti postupak više puta i prikazati rasipanje rezultata pre prihvatanja kalibracije.

Koristiti istu koordinatnu osnovu i jedinice za obe snimljene tačke, poželjno mašinske koordinate. Kalibraciju započetu pre reseta/hominga ili promene koordinatnog sistema ne nastavljati slepo.

Jednoznačno definisati znak:

```text
T = XY položaj vretena kada je alat na referentnoj oznaci
C = XY položaj vretena kada je ista oznaka pod nišanom kamere
offset = T - C

Kada je nova meta pod nišanom pri položaju P:
ciljni_položaj_alata = P + offset
```

Proveriti matematički i u simulatoru pozitivan i negativan pomeraj za obe ose. Ne mešati ovo sa fizičkim vektorom od alata do kamere, koji može imati suprotnu konvenciju znaka.

### Čuvanje i važenje kalibracije

Sačuvati offset, jedinice, identitet kamere, režim slike, položaj nišana, transformacije slike, dogovorenu radnu visinu i datum kalibracije. Prikazati upozorenje ili poništiti kalibraciju kada promena optike/prikaza može promeniti tačku koju predstavlja nišan. Promenu fizičkog nosača/fokusa možda nije moguće automatski detektovati; korisniku dati komandu za ponovnu kalibraciju.

Za jednostavno centriranje pod jednim nišanom dovoljan je izmeren offset. Kalibracija piksel–milimetar, rotacije i distorzije dodatno je potrebna ako se implementira pomeranje na proizvoljnu tačku slike.

### Komanda „Postavi alat na metu“

- Dostupna samo lokalno u UGS-u, kada je kontroler povezan i u odgovarajućem stanju mirovanja.
- Nedostupna tokom slanja/izvršavanja posla, uključujući pauziran posao, kao i tokom hominga, probing-a, alarma i nepouzdane pozicije.
- Pre pomeranja proveriti stanje ponovo u izvršnom sloju; sivo dugme nije dovoljna zaštita.
- Prikazati nameravani pomeraj i zahtevati da je alat podignut na bezbednu visinu. Ne pretpostaviti univerzalnu bezbednu Z vrednost, smer ili slobodan hod.
- Ne pokretati vreteno, spuštanje ili bušenje ovom komandom.
- Koristiti postojeći UGS mehanizam pomeranja i voditi računa o jedinicama i modalnom stanju. Ne ostavljati promenjen G90/G91 ili drugi režim nakon operacije.

## 7. Monitoring tokom posla

Prikazivati dostupne podatke sa jasnim značenjem:

- Povezanost kontrolera, stanje Run/Idle/Hold/Alarm itd.
- Naziv aktivnog fajla bez nepotrebnog izlaganja punog Windows puta.
- Ukupan broj redova, poslati/potvrđeni redovi i procenat na osnovu jasno označene metrike.
- Proteklo vreme i **procenjeno** preostalo vreme.
- Mašinske i radne koordinate, jedinice i dostupne informacije o radnom koordinatnom sistemu.
- Feed, spindle/S vrednost, overrides i drugi parametri samo kada ih UGS pruža.
- Najnovije greške i događaji, sa vremenom.
- Slika kamere, vreme snimanja, starost slike i starost poslednjeg statusa.

Ne nazivati poslati G-code procenat tačno izmerenim fizičkim napretkom. „Svi redovi poslati/potvrđeni“ nije samo po sebi potvrda da je završeno kretanje u baferu kontrolera. Proveriti semantiku događaja završetka u ciljnoj verziji UGS-a i potvrdu završetka kontrolera. Otkazan ili prekinut posao ne sme dobiti obaveštenje „uspešno završen“.

Prijavljene koordinate nisu nezavisno merenje stvarnog položaja bez enkodera. Komandovani RPM/S nije mereni RPM bez povratne informacije. Ne izmišljati temperaturu, opterećenje vretena ili druge senzorske podatke. Nedostupan podatak označiti kao nedostupan, ne kao nulu.

Ako se posao izvršava van UGS-a, UGS možda nema procenat i preostalo vreme. Tada prikazivati samo stvarno dostupne informacije.

## 8. Lokalni server i dashboard za telefon

- Server radi unutar procesa UGS-a ili kroz jasno opravdanu lokalnu prateću komponentu.
- Dashboard prilagoditi telefonu: stanje i napredak na vrhu, najnovija slika, zatim parametri i događaji.
- Prikazati adresu za pristup u UGS panelu. Ne hardkodirati IP adresu; omogućiti podešavanje porta i jasno rešiti zauzet port.
- Omogućiti QR kod za uparivanje ako je praktično. IP/DHCP promene objasniti u korisničkom uputstvu.
- Podrazumevani MVP je **read-only** sa telefona: nema pokretanja posla, jog-a, promene nule, otključavanja alarma ili drugih komandi mašini.
- Status osvežavati približno svake 2 sekunde dok je dashboard otvoren. Periodične slike mogu se osvežavati svakih 15–30 sekundi, uz ručno osvežavanje; podesiti intervale.
- Opcioni brži prikaz/MJPEG stream preko LAN-a dodati tek kada je potvrđeno da ne ometa UGS. Periodične fotografije su dovoljne za početni MVP.
- Prekid veze jasno označiti i zadržati vreme poslednjeg uspešnog ažuriranja. Stara slika ne sme izgledati kao aktuelna.
- Nije potreban port forwarding, Cloudflare Tunnel ili internet za lokalni prikaz.

Predložene rute, koje treba uskladiti sa implementacijom:

```text
GET /                    mobilni dashboard
GET /api/status          jedan objedinjeni snimak statusa
GET /api/events          ograničena lista novijih događaja
GET /api/camera/snapshot najnoviji JPEG
```

Bezbednost pristupa: inicijalno slušati samo na lokalnom interfejsu, a LAN pristup eksplicitno uključiti kroz konfiguraciju. Za LAN pristup obezbediti uparivanje/autentikaciju, proveru sesije za podatke i fotografije, ograničenja zahteva i odsustvo proizvoljnog CORS pristupa. Ne stavljati trajne tokene u URL, QR kod sa trajnim tajnama, logove ili javne resurse. Ako MVP koristi običan HTTP na pouzdanoj kućnoj mreži, jasno dokumentovati da transport nije šifrovan; podržati HTTPS gde je praktično. Ne menjati automatski firewall ili ruter.

## 9. ntfy obaveštenja

U konfiguraciji obezbediti:

- URL ntfy servera (hostovani ntfy.sh ili postojeći self-hosted server).
- Topic, opcioni pristupni token i izbor događaja.
- Prioritete i ograničenje učestalosti.
- Dugme za slanje jasno označenog test obaveštenja.
- Opcioni link na lokalni dashboard.
- Fotografije u obaveštenjima isključene po defaultu; uključivanje mora jasno pokazati da se slika šalje izabranom ntfy servisu.

Predloženi događaji:

| Događaj | Predlog ponašanja |
|---|---|
| Posao uspešno završen | Jedno obaveštenje nakon potvrđenog završetka |
| Alarm ili relevantna greška kontrolera | Obaveštenje višeg prioriteta |
| Veza sa kontrolerom izgubljena tokom posla | Obaveštenje višeg prioriteta |
| Potrebna intervencija/pauza | Podesivo; ne tvrditi uzrok ako nije poznat |
| Posao otkazan | Podesivo, odvojeno od uspešnog završetka |
| Kamera odspojena | Opciono, niži prioritet |

Koristiti HTTPS objave i podržanu ntfy autentikaciju. Za neautentifikovane javne topic-e objasniti da poznavanje naziva omogućava pristup; nasumičan naziv nije zamena za kontrolu pristupa. Ne slati osetljive putanje, tokene ili pune logove u porukama.

Slanje mora biti asinhrono, sa timeout-om, ograničenim redom, backoff-om i spajanjem ponovljenih istih događaja. Kratki mrežni prekid može izazvati odloženo slanje, ali stara upozorenja moraju imati originalno vreme i rok važenja. Kod neizvesnog HTTP ishoda duplikat je moguć; ne obećavati tačno jednu dostavu ako servis to ne podržava.

Link ka LAN dashboardu radi samo kada telefon može da pristupi kućnoj mreži ili VPN-u. Visok ntfy prioritet nije garancija zaobilaženja podešavanja telefona ili isporuke u zadatom roku.

**Ograničenje:** proces koji se srušio ili PC bez interneta ne može odmah poslati ntfy poruku o sopstvenom otkazu. Detekcija izostanka heartbeat-a zahteva nezavisan uređaj/servis i nije deo obaveznog MVP-a. Ako se koristi self-hosted ntfy, proveriti specifične uslove pozadinske dostave za Android/iOS; ne obećavati potpuno lokalni iOS push bez provere.

## 10. Performanse, pouzdanost i čuvanje podešavanja

- Nikakva obrada slike, mreža ili spora disk operacija na Swing UI niti ili niti koja obrađuje komunikaciju sa kontrolerom.
- UGS događaj obrađivati kratko: kopirati potrebne podatke, a ostalo izvršiti u pozadini.
- Ograničiti broj niti, memoriju, istovremene klijente i veličinu redova.
- Čuvati najnoviji kadar i odbacivati zastarele kadrove; ne akumulirati neograničen video bafer.
- Smanjiti rezoluciju i kompresovati pregled na PC-u; ne slati nepotrebno 8 MP fotografiju pri svakom statusnom upitu.
- Gubitak kamere, telefona, ntfy servisa ili interneta ne sme zaustaviti lokalni G-code posao.
- Pravilno osloboditi kameru, server, niti i slušaoce pri gašenju/onemogućavanju dodatka.
- Sačuvati kalibraciju, podešavanja kamere, dashboarda i obaveštenja. Koristiti odgovarajući NetBeans/UGS mehanizam za položaj prozora.
- Za tajne koristiti platformin keyring gde je dostupan. Ne uključivati tokene u običan export bez izričitog izbora.
- Ne prepisivati postojeća UGS podešavanja, makroe, keymap ili raspored drugih prozora.
- Dokumentovati gde se nova podešavanja nalaze i kako se prenose na drugi računar. Na drugom računaru proveriti identitet kamere i kalibraciju.

## 11. Predloženi redosled implementacije

1. Utvrdi ciljnu verziju UGS Platform i lokalne instrukcije repozitorijuma. Pregledaj postojeći plugin, backend API, dostupne podatke i događaje posla.
2. Napravi modul sa panelom i konfiguracijom koji se može instalirati i ukloniti.
3. Dodaj prikupljanje statusa i lokalni read-only dashboard. Koristi simulirane podatke gde hardver nedostaje.
4. Dodaj ntfy događaje i test slanja, uz jasno razlikovanje završetka, otkazivanja i prekida.
5. Dodaj UVC kameru, nišan i LAN fotografije.
6. Dodaj ručnu kalibraciju i lokalnu komandu pomeranja, sa proverama stanja.
7. Proveri ponašanje pod opterećenjem i pri prekidima, zatim spakuj dodatak i dokumentaciju.

Nepoznate hardverske detalje pitaj kada postanu potrebni. U međuvremenu nastavi delove koji od njih ne zavise. Automatsko pomeranje stvarne mašine, pokretanje vretena i bušenje radi testiranja nisu podrazumevano odobreni ovim uputstvom; za njih dogovori kontrolisan test sa korisnikom.

## 12. Provera i kriterijumi prihvatanja

### Softverska provera

- Modul se uspešno gradi i učitava u ciljnoj verziji UGS-a; postojeći raspored/podešavanja ostaju očuvani.
- Dashboard prikazuje dosledan status, dostupnost i vreme poslednjeg podatka.
- Autentikacija pokriva status, događaje i fotografije; neovlašćen klijent nema pristup.
- Promene statusa: start, run, hold, resume, alarm, cancel, disconnect i završetak imaju očekivano značenje.
- Potvrda poslednjeg G-code reda dok je kontroler još Run ne šalje prerano obaveštenje o završetku.
- ntfy greške, timeout-i i ponovljeni događaji ne blokiraju UGS i ne stvaraju neograničen red poruka.
- Znak XY offset-a, jedinice i blokiranje pomeranja tokom aktivnog/pauziranog posla imaju ciljane testove.
- Kamera se može odspojiti/ponovo povezati; odsustvo kamere ne onemogućava monitoring.
- Podešavanja opstaju nakon ponovnog pokretanja; promenjeni režim slike ne koristi nevažeću kalibraciju bez provere.

### Provera na stvarnom sistemu uz korisnika

- Telefon preko kućne mreže vidi dashboard i fotografije sa stvarnim vremenom snimanja.
- ntfy test stiže i kada je telefon zaključan, uz odgovarajuća podešavanja aplikacije i mreže.
- Tokom reprezentativnog dužeg posla istovremeno rade UGS, kamera i dashboard; nema novih komunikacionih grešaka ili nekontrolisanog rasta memorije. Uporedi sa radom bez dodatka i zabeleži trajanje/uslove testa.
- Isključivanje interneta ne prekida obradu; lokalni prikaz ostaje funkcionalan dok LAN radi.
- Kalibraciju ponoviti više puta, npr. deset, i zabeležiti rasipanje pozicija. Proveriti i stvarno poravnanje alata sa referencom: dobra ponovljivost sama po sebi ne dokazuje odsustvo sistematske greške.
- Proveriti ponašanje pri promeni visine površine i dokumentovati granice važenja kalibracije.

Ako neki od ovih testova nije izvršen, označi ga kao neizvršen i navedi šta nedostaje. Ne predstavljati simulator kao potvrdu fizičke tačnosti ili pouzdanosti mašine.

## 13. Šta predati korisniku

- Izvorni kod i tačnu kompatibilnu verziju UGS-a.
- Instalacioni paket dodatka, npr. `.nbm` ako ga ciljna distribucija podržava, ili dokumentovan alternativni način instalacije.
- Kratko uputstvo za instalaciju, kameru, kalibraciju, pristup sa telefona i ntfy.
- Uputstvo za backup/restore i uklanjanje dodatka bez gubitka postojećih UGS podešavanja.
- Pregled implementiranih i odloženih funkcija, rezultate testova i preostale hardverske nepoznanice.

## 14. Polazni izvori

Pre implementacije proveriti aktuelni kod i dokumentaciju; `master` grana nije nužno ista kao instalirana verzija korisnika.

- UGS repozitorijum i razvoj: https://github.com/winder/Universal-G-Code-Sender
- Read-only backend (status, pozicije, brojevi redova, vremena, događaji): https://github.com/winder/Universal-G-Code-Sender/blob/master/ugs-core/src/com/willwinder/universalgcodesender/model/BackendAPIReadOnly.java
- Backend komande: https://github.com/winder/Universal-G-Code-Sender/blob/master/ugs-core/src/com/willwinder/universalgcodesender/model/BackendAPI.java
- ntfy objave, prioriteti, linkovi, slike i autentikacija: https://docs.ntfy.sh/publish/
- ntfy konfiguracija i ograničenja self-hosting-a: https://docs.ntfy.sh/config/
- OpenCV kalibracija, za naprednije funkcije: https://docs.opencv.org/5.0/tutorials/calib3d/camera_calibration/camera_calibration.html

**Prvi cilj:** jedan stabilan UGS dodatak, lokalni dashboard na telefonu i ntfy za važne događaje, uz ručnu kameru/nišan i proverenu kalibraciju. Cloud backend, namensku mobilnu aplikaciju i automatsko prepoznavanje meta ostaviti kao nezavisne kasnije nadogradnje.
