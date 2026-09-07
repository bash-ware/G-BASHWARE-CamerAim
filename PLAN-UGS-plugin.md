# Plan: UGS kamera, određivanje nule i lokalni monitoring

Datum: 6. septembar 2026.

Osnova: `UGS-kamera-lokalni-monitoring-ntfy-uputstvo.md` i korisnikov zahtev. Plan se realizuje u modulu `ugs-camera-monitor`; trenutni paket već obuhvata prvu verziju kamere, nišana, X/Y ofseta, kontrolisanog pomeraja i postavljanja XY nule. Hardverska provera na stvarnoj mašini još nije urađena.

## 1. Cilj i prioriteti

Jedan dodatak za UGS Platform na Windows računaru:

1. **Primarno:** živa slika USB kamere, nišan, kalibrisan XY ofset kamera–alat i vođen postupak određivanja radne XY nule.
2. **Praćenje obrade:** slika dostupna tokom celog posla, status mašine, napredak, trajanje, procenjeno preostalo vreme i dostupni parametri.
3. **Telefon:** lokalni web dashboard i ntfy push poruke sa ključnim parametrima.

Redosled razvoja se menja u odnosu na polazni dokument: kamera i određivanje nule dolaze pre kompletnog dashboarda i notifikacija.

## 2. Arhitektura

```text
USB/UVC kamera ──► PC / UGS dodatak ◄── UGS API za status i komande
                       │
                       ├── lokalni panel: kamera, nišan, kalibracija, XY nula
                       ├── zajednički status posla i istorija događaja
                       ├── ugrađeni lokalni web server ──► browser telefona
                       └── pozadinsko HTTPS slanje ──► ntfy ──► telefon
```

Kamera je priključena direktno na PC preko USB-a. Dodatak je nezavisan od modela CNC kontrolera: status i komande koristi isključivo kroz postojeći UGS API. Model ploče i firmware nisu preduslov za razvoj; dostupnost pojedinačnih parametara utvrđuje se kroz UGS.

Server radi na istom PC-u, u procesu UGS-a. Dashboard i ntfy koriste isti model statusa; slanje poruka ne zavisi od otvorenog browsera. Nema druge serijske veze ka kontroleru.

Predložene interne komponente su adapter za UGS, servis kamere, servis kalibracije, lokalne komande pozicioniranja/nule, model posla, HTTP server i ntfy klijent. To su nazivi odgovornosti, ne potvrđeni UGS API-ji.

Lokalni prikaz radi bez interneta. Ako se koristi ntfy.sh, samo izabrane poruke napuštaju lokalnu mrežu. Podržati i postojeći self-hosted ntfy; uslove mobilne dostave proveriti prema telefonu. Ugrađeni dashboard server sam po sebi nije ntfy server.

## 3. Ključni tok: od nišana do radne nule

### Kalibracija

Podržati ručni unos poznatog XY ofseta i merni postupak. Ručno unet ofset označiti kao neproveren dok se ne proveri na referenci.

1. Alatom označiti referentnu tačku i zabeležiti mašinske XY koordinate `T`.
2. Na utvrđenoj bezbednoj visini ručno centrirati istu referencu pod nišan kamere i zabeležiti mašinske XY koordinate `C`.
3. Izračunati `offset = T - C`, sa svim vrednostima u istim jedinicama.
4. Ponoviti merenje, prikazati pojedinačne rezultate, srednju vrednost i najveće odstupanje. Za prihvatanje na mašini predvideti deset ponavljanja i nezavisnu proveru poravnanja alata.
5. Sačuvati kameru, režim slike, nišan, transformacije, visinu/udaljenost površine, datum i ofset.

Promena nosača, fokusa, relevantnih postavki slike ili radne visine zahteva proveru kalibracije. Tačnost se prihvata prema dogovorenoj toleranciji i fizičkom merenju, ne prema rezoluciji kamere.

### Određivanje nove XY nule

1. Korisnik izabere aktivni radni koordinatni sistem i proveri kalibraciju.
2. Ručno dovede željenu oznaku pod nišan, uz živu i svežu sliku. Trenutni položaj vretena je `P`.
3. Dodatak prikaže cilj `P + offset`, pomeraj po obe ose i uslov bezbedne visine/slobodnog puta.
4. Lokalna komanda **„Pomeri alat na poziciju nišana“** izvrši samo XY pomeranje kroz UGS, uz ponovnu proveru stanja neposredno pre izvršenja. Ne uključuje vreteno niti menja Z.
5. Posle potvrđenog završetka pomeranja i svežeg statusa, zasebna lokalna komanda **„Postavi XY nulu ovde“** postavi radne X i Y na nulu u izabranom WCS-u. Proveriti rezultat čitanjem statusa i sačuvati događaj. Ne menjati mašinsku nulu niti druge WCS-ove.

**Z nula se određuje zasebno**, postojećim UGS probing-om ili ručnim postupkom. Jedna kamera sa XY ofsetom ne meri visinu alata.

Obe komande zahtevaju pouzdanu poziciju, povezanu mašinu u mirovanju i odsustvo aktivnog ili pauziranog posla, hominga, probing-a i alarma. Blokirati ih tokom druge operacije; proveriti granice hoda gde postoje pouzdani podaci. Jedinice i modalno stanje moraju ostati očuvani. Reset/homing tokom započetog merenja prekida taj postupak.

### Konkretne kontrole prve verzije

- Polje **„Ofset X“**: relativni pomeraj alata po X osi kada je meta centrirana pod nišanom.
- Polje **„Ofset Y“**: relativni pomeraj alata po Y osi kada je meta centrirana pod nišanom.
- Izbor jedinice `mm` ili `inch`; vrednosti se čuvaju zajedno sa jedinicom.
- Polje **„Jog brzina“** u izabranoj jedinici po minutu.
- Stalni tekst neposredno uz dugme, na primer: **„Alat će se pomeriti: X +12,000 mm, Y −3,500 mm“**.
- Dugme **„Sačuvaj ofset“** proverava da su vrednosti konačni brojevi i trajno ih čuva.
- Dugme **„Pomeri alat na poziciju nišana“** ponovo učitava vrednosti iz polja, proverava stanje UGS-a i traži potvrdu konkretnog XY pomeraja. Izvršava jedan relativni jog za tačno zadati X/Y ofset, bez Z komponente.
- Dugme **„Postavi XY nulu ovde“** dostupno je kao odvojena radnja tek kada mašina miruje; postavlja obe radne koordinate na nulu preko UGS API-ja.

Konvencija znaka u korisničkom interfejsu je namerno operativna: uneti X/Y označava smer i dužinu **kretanja alata**, a ne apstraktni fizički vektor kamera–alat. Pozitivan X uvek znači relativni pomeraj `+X`; negativan Y znači relativni pomeraj `−Y`. Zato dugme ne invertuje znake.

## 4. Kamera i interfejs

Glavni UGS panel daje najveći prostor slici: tanak podesiv nišan, zoom, izbor kamere/režima, status kadra, ofset i komande kalibracije/pozicioniranja. Zamrzavanje slike je jasno označeno i onemogućava korišćenje tog kadra kao aktuelne potvrde pozicioniranja.

Za prvu verziju: jedna standardna UVC kamera, ručno centriranje, bez automatskog prepoznavanja mete. Konkretna biblioteka za kameru bira se tek nakon probe kompatibilnosti sa ciljnom Java/UGS verzijom i Windows kamerom. Fokus/ekspozicija dostupni su samo ako ih uređaj podržava.

Jedna kamera može služiti centriranju i praćenju, ali njen uzak kadar možda neće pokazivati celu obradu. To proveriti prema montaži; druga pregledna kamera ostaje proširenje.

## 5. Parametri posla

| Podatak | Planirano značenje |
|---|---|
| Stanje | Povezanost, Run/Idle/Hold/Alarm i svežina statusa |
| Posao | Naziv fajla, identitet sesije posla, početak i završetak |
| Napredak | Poslati/potvrđeni redovi i procenat sa jasno označenom osnovom |
| Vreme | Proteklo vreme, odvojeno trajanje pauza gde se može pouzdano izračunati |
| Procena | Procenjeno preostalo vreme i očekivano vreme završetka |
| Brzina | Prijavljeni feed, jedinice i override, ako su dostupni |
| Vreteno | Prijavljena S/RPM vrednost, uz oznaku da nije nezavisno merenje |
| Položaj | Mašinske/radne koordinate i dostupni WCS podaci |
| Kamera/događaji | Poslednji kadar sa vremenom; alarmi, pauze, prekidi i otkazivanje |

Za svako polje utvrditi izvor, jedinice i značenje u ciljnoj verziji. Nepostojeći podaci prikazuju se kao nedostupni. ETA se zasniva na provereno dostupnoj UGS proceni; tokom pauze označiti je kao zadržanu/nepouzdanu, a posle nastavka osvežiti. Ne uvoditi prividno preciznu procenu samo iz procenta redova.

Završetak zahteva potvrdu završetka slanja i izvršavanja kroz dostupne UGS događaje i status. Poslednji potvrđen red dok je mašina još Run nije dovoljan. Cancel, alarm, disconnect i nepoznat ishod ostaju odvojeni od uspeha. Ako se posao izvršava van UGS-a, prikazati samo podatke koje UGS stvarno ima.

## 6. Lokalni dashboard i ntfy

Dashboard za telefon prikazuje stanje i vreme na vrhu, zatim kameru, ostale parametre i događaje. Početno osvežavanje: status na približno 2 sekunde, fotografija na 15–30 sekundi, uz ručno osvežavanje. Brži LAN video dodati posle provere opterećenja. Lokalni panel kamere ima živ prikaz od prve funkcionalne verzije.

Predložene rute: `/`, `/api/status`, `/api/events`, `/api/camera/snapshot`. Telefon u MVP-u samo prati: nema komandi kretanja, nule ili pokretanja posla.

Server inicijalno sluša lokalno; LAN pristup korisnik uključuje. Uparivanje kratkotrajnim jednokratnim kodom, zatim proverena sesija za sve podatke i slike, uz ograničenje pokušaja i zahteva. Trajne tajne ne idu u URL. Port je podesiv, adresa vidljiva u UGS-u. Dokumentovati nešifrovan HTTP na pouzdanom LAN-u; HTTPS uključiti gde je praktično. Bez automatskih promena rutera/firewall-a.

ntfy poruke uključuju događaj, naziv posla, originalno vreme, napredak, trajanje i raspoloživu procenu/parametre. Na primer, ilustrativni izveštaj: „Obrada traje 00:42; potvrđeno 63% redova; procenjeno još 00:25; feed 600 mm/min.“

Podrazumevani događaji: potvrđen završetak, alarm i gubitak veze tokom posla. Podesivi: početak, pauza, nastavak, otkazivanje, gubitak kamere i periodični izveštaj, npr. svakih 15 minuta. Periodika je opciona da telefon ne dobija poruku pri svakom osvežavanju statusa.

Slanje ide direktno iz dodatka u pozadini, sa ograničenim redom, timeout-om, ponovnim pokušajima i spajanjem ponovljenih upozorenja. Podesivi su server, topic, token, prioriteti i događaji. Fotografije su početno isključene. Podržati HTTPS i ntfy autentikaciju; javni topic nije privatan samo zato što mu je naziv nasumičan. LAN link radi samo uz pristup toj mreži/VPN-u.

## 7. Faze i kriterijumi izlaska

| Faza | Rad | Uslov završetka |
|---|---|---|
| 0 — Kompatibilnost | Utvrditi UGS Platform verziju, Java okruženje i odgovarajući tag izvornog koda; proveriti API, komande WCS-a i događaje | Utvrđeno: lokalna instalacija UGS Platform 2.1.6, Java 17 runtime, izvorni tag `v2.1.6`; potvrđeni `adjustManualLocation` i `setWorkPosition` API pozivi |
| 1 — Osnova i kamera | Modul, panel, podešavanja, UVC proba, nišan, zoom, svežina slike | **U toku:** NBM se učitava u izolovanom UGS 2.1.6 profilu; implementirani su UVC izbor, prikaz, nišan i zamrzavanje. Ostaju proba sa stvarnom kamerom, zoom i oporavak od odspajanja. |
| 2 — Ofset i XY nula | Unos/merenje ofseta, profil kalibracije, kontrolisano XY pomeranje i zaseban upis nule | **U toku:** implementirani su unos/čuvanje X/Y, mm/inch konverzija, cilj `P + offset`, relativni XY jog, zasebna XY nula i softverske zabrane. Ostaju merni čarobnjak, profil kalibracije i fizička provera. |
| 3 — Monitoring | Jedinstven model statusa i životnog ciklusa posla, trajanje/ETA | Start/hold/resume/cancel/alarm/disconnect/završetak imaju ispravnu semantiku |
| 4 — Telefon | Lokalni server, uparivanje, dashboard, fotografije | Telefon vidi sveže podatke; neovlašćeni klijent nema pristup |
| 5 — ntfy | Događaji, parametri u poruci, opciona periodika, red i retry | Provereno slanje i greške; zaključan telefon prima test u stvarnim uslovima |
| 6 — Isporuka | Test dužeg rada, restart/cleanup, pakovanje i uputstva | Paket za ciljnu verziju, rezultati provera i jasno navedene preostale hardverske provere |

Obrada slike, HTTP, disk i ntfy ne rade na Swing UI niti ili niti komunikacije s kontrolerom. Čuvati najnoviji kadar, ograničiti redove/klijente/memoriju i osloboditi resurse pri gašenju. Otkaz kamere, LAN-a ili interneta ne sme prekidati G-code posao. Tajne čuvati u platforminom keyring-u gde je dostupan.

Obavezne ciljane provere: pozitivan/negativan XY ofset, mm/inch, promena WCS-a, zastareo status, završetak posle stvarnog mirovanja, zabrane tokom pauziranog posla, reconnect kamere, nedostupan ntfy, sesije dashboarda i očuvanje konfiguracije. Uporediti duži reprezentativan posao sa uključenim i isključenim dodatkom.

Testovi sa stvarnim pomeranjem, vretenom ili pravljenjem reference dogovaraju se sa korisnikom. Simulator dokazuje softversko ponašanje, ne fizičku tačnost. Nezavisan nadzor pada PC-a, cloud dashboard, posebna mobilna aplikacija i automatsko prepoznavanje meta ostaju za kasnije.

## 8. Podaci potrebni pre rada sa konkretnim sistemom

- Tačna verzija UGS-a i potvrda da je UGS Platform.
- Kamera ili planirani model, montaža i udaljenost od radne površine.
- Potrebna tolerancija centriranja u mm i da li se visina površine menja.
- Android ili iPhone; ntfy.sh ili postojeći sopstveni ntfy server.

Ove nepoznanice ne sprečavaju pripremu modula i simulaciju, ali određuju konačnu kompatibilnost i kriterijume prihvatanja na mašini.

## 9. Provereni polazni izvori

Pregledani su 6. septembra 2026; `master` nije potvrđena korisnikova verzija.

- [UGS Platform struktura modula](https://github.com/winder/Universal-G-Code-Sender/tree/master/ugs-platform) — osnova za proveru integracije u ciljnoj verziji.
- [UGS BackendAPIReadOnly](https://raw.githubusercontent.com/winder/Universal-G-Code-Sender/master/ugs-core/src/com/willwinder/universalgcodesender/model/BackendAPIReadOnly.java) — sadrži pristup statusu, pozicijama, brojačima redova, trajanju/proceni i slušaocima događaja. Implementaciju i tačnu semantiku tek treba proveriti na odabranom tag-u.
- [ntfy objavljivanje](https://docs.ntfy.sh/publish/) — HTTP objave, prioriteti, linkovi, prilozi i autentikacija.

Konačna isporuka: izvorni kod, kompatibilni instalacioni paket, uputstva za instalaciju/kalibraciju/telefon/ntfy, backup i uklanjanje, plus zapis testova i granice fizički potvrđene preciznosti.

## 10. Buduće proširenje: G-code overlay preko slike kamere

Plugin može preuzeti putanju trenutno učitanog G-code fajla iz UGS-a i nacrtati je kao providan XY overlay preko žive slike kamere. Ovo proširenje dolazi posle stabilizacije kamere, ofseta, praćenja posla i lokalnog servera.

Predvideti dva režima prikaza:

1. **Lokalni prikaz oko nišana** — prikazuje samo deo putanje oko trenutne pozicije i koristi se za precizno pozicioniranje komada.
2. **Pregled celog posla** — prikazuje kompletnu projektovanu XY putanju preko vidljivog materijala.

Putanju prikazivati po stanju izvršavanja: neizvršeni segmenti sivom, završeni zelenom, a trenutni segment i položaj alata jasno istaknutom bojom. `G0` brza kretanja, `G1` radna kretanja i `G2/G3` lukovi treba vizuelno razlikovati; lukove pre prikaza pretvoriti u dovoljno gust niz tačaka. Početni prikaz je dvodimenzionalan, dok se Z vrednost može sakriti ili predstaviti bojom.

Za pravilno poklapanje G-code putanje i stvarnog komada potrebna je posebna kalibracija projekcije:

- skala slike, odnosno broj piksela po milimetru;
- rotacija X/Y osa kamere u odnosu na ose mašine;
- pomeraj koordinatnog početka, uz postojeći kamera–alat XY ofset;
- perspektivna transformacija pomoću četiri poznate tačke kada kamera nije normalna na radnu površinu.

Transformacija ide redom: G-code radne koordinate → aktivni WCS i trenutna mašinska pozicija → koordinatni sistem kamere sa kamera–alat ofsetom → kalibrisana pozicija piksela. Promena visine radne površine, položaja ili ugla kamere zahteva novu proveru projekcione kalibracije.

Kontrole buduće verzije obuhvataju uključivanje/isključivanje overlaya, providnost, izbor lokalnog/celog prikaza, boje segmenata, prikaz brzih kretanja i postupak kalibracije sa poznatim tačkama. Overlay je samo pomoćni prikaz: ne menja G-code, radne koordinate niti komande kretanja.

## 11. Buduće proširenje: automatsko fotografisanje i stitching radne površine

Za kameru postavljenu približno 90 mm iznad radne površine predvideti automatsko skeniranje cele površine 400 × 300 mm. Plugin iz stvarno izmerenog vidnog polja kamere i izabranog preklapanja izračunava potreban broj položaja, pomera kameru kroz cik-cak mrežu i na svakom položaju snima stabilan kadar. Rezultat je jedna velika georeferencirana fotografija čiji pikseli odgovaraju poznatim mašinskim XY koordinatama.

Postupak skeniranja:

1. Zahtevati povezanu mašinu u stanju `Idle`, ugašeno vreteno, bezbednu i nepromenjenu Z visinu i potvrđene granice skeniranja.
2. Izmeriti stvarno vidno polje kamere na radnoj visini i izabrati preklapanje kadrova, početno 30–40%.
3. Uračunati kamera–alat XY ofset i proveriti da optički centar može fizički dohvatiti sve potrebne položaje unutar granica hoda.
4. Napraviti cik-cak mrežu položaja radi kraćeg ukupnog kretanja.
5. Posle svakog pomeranja sačekati potvrđeno mirovanje i kratko smirivanje vibracija, proveriti svežinu kadra i tek zatim fotografisati.
6. Svakom kadru sačuvati mašinsku XY poziciju, Z visinu, vreme, rezoluciju i identitet kalibracionog profila.
7. Obraditi i spojiti kadrove, zatim rezultat kropovati na potvrđene granice radne površine 400 × 300 mm.

Broj snimaka računati iz dimenzije površine `L`, vidnog polja kadra `F` i preklapanja `o`: `n = ceil((L - F) / (F × (1 - o))) + 1`. Konačan broj zavisi od stvarnog objektiva i senzora. Ilustrativno, vidno polje 80 × 60 mm sa 30% preklapanja zahteva približno 7 × 7, odnosno 49 fotografija; vidno polje 120 × 90 mm približno 5 × 5, odnosno 25 fotografija.

### Kalibracija slična LightBurn pristupu

Kalibraciju podeliti na dva nezavisna dela:

- **Intrinzična kalibracija kamere i objektiva:** više snimaka štampane šahovske ili ChArUco table radi izračunavanja žižnih parametara, optičkog centra i radijalne/tangencijalne distorzije. Profil važi samo za istu kameru, rezoluciju, fokus i relevantne postavke slike.
- **Kalibracija kamere prema radnoj ravni:** najmanje četiri poznate tačke na radnoj površini sa potvrđenim mašinskim koordinatama radi izračunavanja skale, rotacije, pomeraja i perspektivne transformacije. Profil važi za istu montažu i visinu radne ravni.

Redosled obrade pojedinačnog kadra: originalni kadar → korekcija distorzije sočiva → uklanjanje nevažećih ivica → perspektivna transformacija u pogled odozgo → pretvaranje piksela u milimetre → početno postavljanje prema mašinskoj XY poziciji → fino poravnanje preklopa → spajanje i izjednačavanje osvetljenja. Poznate mašinske koordinate su osnovni izvor poravnanja; prepoznavanje detalja u preklopu služi samo za finu korekciju kada kadar ima dovoljno korisnih detalja.

Tokom cele sesije zaključati fokus, ekspoziciju, zoom i balans bele gde kamera to podržava. Promena nosača, ugla kamere, rezolucije, fokusa ili visine površine poništava odgovarajuću kalibraciju. Neravna ili trodimenzionalna površina stvara paralaksu koju jedna ravanska perspektivna transformacija ne može potpuno ukloniti.

Kontrole proširenja obuhvataju granice površine, radnu Z visinu, procenat preklapanja, vreme smirivanja, pregled izračunate mreže i broja snimaka, pokretanje/pauzu/bezbedan prekid, napredak i pregled konačnog mozaika. Zaustavljanje, alarm, reset, gubitak veze ili zastareo položaj odmah prekidaju skeniranje bez nastavka kretanja.

Konačni mozaik koristi se kao podloga za budući G-code overlay iz prethodnog odeljka, izbor XY tačke mišem i vizuelnu proveru položaja komada. Automatsko skeniranje ostaje lokalna, potvrđena operacija u UGS-u i ne pokreće se sa telefonskog dashboarda.
