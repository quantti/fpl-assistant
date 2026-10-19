# Fly-katko 16.10.2026: selvitys ja kustannusneutraali suunnitelma

**PROGRESS:** Selvitys valmis; omistaja valitsi yhden koneen 17.10.2026. Tässä selvityksessä ei tehty toteutusta tai tuotantomuutoksia.

> Historiallinen 17.10.2026 tilannekuva, ei nykytilan operointiohje. Sittemmin valvonta
> on järjestetty Uptime Kumalla, vanha owned-sync-runtime on korvattu incremental importerilla
> ja käyttämätön Actions-heartbeat on poistettu. Ajantasainen tila: `docs/STATUS.md`;
> importer-menettely: `docs/IMPORTER-CUTOVER-2026-10-17.md`. Alla olevat koneiden tilat,
> hälytyspuutteet ja toteutusehdotukset kuvaavat selvityshetkeä, eivät tuoretta tarkistusta.

## Omistajan tarkennukset 17.10.2026 — ohittavat alkuperäisen ehdotuksen

- Pysytään yhdessä nykyisen kokoisessa (1 GiB) Fly-koneessa. Toinen kone poistetaan
  hallitusti vasta sync-omistajuuden ja aktiivisten töiden varmistamisen jälkeen.
- Hetzner-VPS ei ole enää käytössä. Alla olevien kohtien 2–4 VPS-toteutus ja
  kustannusraamin oletus jo maksetusta VPS:stä ovat **vanhentuneita, eivät toteutusohjeita**.
- Valvonnaksi valitaan ulkoinen maksuton HTTPS-valvontapalvelu. Sen tarkistusväli ja
  puhelinhälytysten maksuttomuus varmistetaan ennen valintaa; 1–2 minuutin tavoitetta
  ei vielä luvata. Oma tarkistin ja sen erillinen heartbeat eivät ole oletusratkaisu.
- Häiriölokien automaattiselle talteenotolle on vielä valittava toteutus ilman VPS:ää.
  Tavallinen HTTPS-valvonta ei kerää Fly-lokeja.
- `always`-restart ja hallittu yhden koneen palautuminen säilyvät suunnitelmassa.
  Yksi kone ei takaa katkottomuutta. Tuotannon alasajoa ei ole tehty.

## Varmistettu näyttö

Tutkimus 17.10.2026 noin 09:17–09:22 UTC. Fly CLI:n list/status/logs/releases,
Machines API:n GET events, GitHubin workflow-ajot ja repository variables/secrets
(nimet, ei arvoja), nykyinen masterin fly.toml sekä julkinen health-GET.
Paikallinen checkout on 28.9. tasolla ja sisältää käyttäjän keskeneräisiä muutoksia;
niihin ei koskettu. Fly-asetukset tarkistettiin siksi myös GitHubin masterista ja
molempien koneiden todellisesta konfiguraatiosta.

Kaikki seuraavat kellonajat UTC; Suomen aika 16.10. on UTC+3.

| Aika 16.10. | Havainto |
|---|---|
| 17:55:44–17:57:27 | Alkuperäinen kone `8151d9a97e7538` poistui toistuvasti koodilla 1. Säilyneet tapahtumat näyttävät restart_count 7, 8, 9 ja 10 sekä restarting=true. |
| 17:58:25.967 | exit_code=1, oom_killed=false, requested_stop=false, restart_count=10, restarting=false. Kone jäi stopped-tilaan. |
| 18:03:59 | Uusi start-pyyntö, source=user. Tämä ei yksilöi ihmistä tai asiakasohjelmaa. |
| 18:04:17.573 | Sama exit_code=1; restart_count=10 ja restarting=false. Stopped-tapahtuma kirjattiin 18:04:21.664. |
| 18:06:22 | Toinen kone `6832545c205238` luotiin; source=user. |
| 18:06:30 | Toinen kone started. Tämä ei vielä todista HTTP-palvelun palautumisen hetkeä. |
| 18:09:37–38 | Alkuperäiselle koneelle start-pyyntö ja started-tapahtuma. |

Molemmat koneet ovat nyt started, shared-cpu-1x / 1024 MiB / cdg, samassa
release v167:n imagessa. Viimeisin julkaisu on 13.10.; 16.10. illan tarkistettu
onnistunut GitHub-ajo 38963430442 oli pull_request-ajo, jonka deploy-job ohitettiin.
Julkinen https://fpl-sidekick.com/actuator/health palautti tutkimuksessa UP.

Molempien koneiden nykyinen restart-politiikka on `on-failure`, `max_retries=10`.
`auto_stop_machines=off`, `auto_start_machines=true` ja `min_machines_running=1`
ovat jo käytössä. Kyse ei ole siitä, että idle-autostop pitäisi vasta poistaa.
Fly-dokumentaatio kuvaa on-failure-politiikan kymmenen yrityksen rajalla viiden
minuutin ikkunassa. API-tapahtumat vahvistavat tässä rajalle päätymisen.
Health-check ohjaa reititystä, mutta ei itsessään käynnistä konetta uudelleen.
Minimikoneasetus ei ole crash-loopista palautumisen takuu.

### Mitä ei voida päätellä

Kaatumissarja ja automaattisen uudelleenkäynnistyksen loppuminen on varmistettu.
**Sovelluksen alkuperäinen poikkeus ja katkon tarkka alkamis-/päättymisaika ovat
selvittämättä.** Saatavilla ollut Fly-lokipuskuri sisälsi nykyisiä lokeja eikä
16.10. käynnistysvirhettä. Ei näyttöä OOM-killistä näissä exit-tapahtumissa;
tämä ei sulje pois sovelluksen sisäistä muistivirhettä. DB-, verkko-, host- tai
sovellusvikaa ei pidä nimetä juurisyyksi ilman alkuperäistä virhelokia.

Seuraava juurisyyaskel: omistajan Fly Logs/Grafana- tai muu säilynyt loki
16.10. noin 17:45–18:10 UTC, kone 8151d9a97e7538. Tarvitaan ensimmäinen
käynnistymisen estävä poikkeus ja sen Caused by -ketju, ei vain exit-rivi.
Jos historiatietoa ei enää ole, kirjataan juurisyy tuntemattomaksi ja kerätään
seuraavan tapauksen näyttö automaattisesti; ei arvata korjausta.

### Erilliset nykyiset riskit

- Nyt on **kaksi**, ei yksi laskutettava 1 GiB:n kone. Tarkkaa laskua/credittejä
  ei tarkistettu, mutta tämä ei vastaa yhden koneen kustannusraamia.
- Uuden koneen lokissa toistuu `RuntimeFenceLostException`, sekä
  `Owned-sync runtime fence is no longer current for 2026-27` että
  `Planner runtime epoch is stale`, vaikka koneen HTTP-health on UP.
  Tämä osoittaa valvonnan aukon ja sync-ongelman, ei todista eilisen katkon syytä.
  Pelkkä kahden replikan jättäminen päälle vaatii erikseen sync-omistajuuden,
  ajastusten ja muistissa olevan rate limitin monireplikaturvallisuuden.
- `.github/workflows/owned-sync-heartbeat.yml` on ehdollinen muuttujalle
  `FPL_SYNC_MONITOR_ENABLED=true`. Repository variable -lista oli tyhjä,
  monitorin repository secretit puuttuivat ja viimeiset ajot olivat skipped.
  Se ei siis tällä hetkellä hälytä. Nimellinen cron on 10 minuuttia, ja
  todelliset ajot ovat olleet tuntien välein: ei sovellu nopean hälytyksen perustaksi.

## Suositus: yksi nykyisen kokoinen kone, jatkuva palautuminen ja ulkoinen valvonta

Tavoite on estää **pysyväksi jäävä** katko ja saada hälytys noin 1–2 minuutissa.
Yhdellä koneella ei luvata katkottomuutta, välitöntä ilmoitusta tai Fly-aluevian
selviytymistä. JVM:n käynnistyminen aiheuttaa edelleen katkon.

### 1. Säilytä yksi kone ja muuta restart-politiikka

Lisää fly.toml:iin app-prosessin eksplisiittinen `always`-restart-politiikka.
Pidä nykyinen 1 GiB, autostop off ja autostart true. Tarkista toteutuksen yhteydessä
Fly-konfiguraation oikea syntaksi ja deployn jälkeen koneen todellinen restart.policy.
Tämä ei korjaa pysyvää käynnistysvirhettä, mutta ei jätä ohimenevän vian jälkeen
palvelua kymmenen yrityksen rajaan. Crash-loop hälytetään, ei piiloteta.

Toisen koneen poisto on **erillinen hyväksyttävä tuotantotoimi**, ei tutkimuskomento.
Selvitä ensin kummalla koneella on nykyinen sync-omistajuus, aktiiviset työt ja
mahdolliset ratkaisemattomat permitit. Säilytä toimiva omistaja ja poista toinen
hallitusti käyttäen olemassa olevaa drain-/epoch-menettelyä. Älä päättele omistajaa
koneen iästä tai vihreästä HTTP-checkistä. Älä tyhjennä permittejä tai muuta sync-modea
pelkän verkkopalvelun palauttamiseksi. `--ha=false` ei yksin ole näyttö siitä,
että jo olemassa oleva toinen kone olisi poistunut.

Hyväksyntä: yksi halutun kokoinen kone, restart=always, HTTP ja sync erikseen
terveitä. Hallittu prosessin virhe-exit palautuu ilman käyttäjän start-komentoa;
myös yli kymmenen epäonnistumisen jälkeinen palautuminen testataan eristetyssä
ympäristössä, ei tahallaan tuotannossa. Ei tuotantovikatestiä ilman omistajan lupaa.

### 2. Ulkoinen tarkistus olemassa olevalla Hetzner-VPS:llä

Pieni systemd timer + oneshot, ei uutta palvelinta tai monitorointipinoa:

- Tarkistus 30 sekunnin välein, päällekkäiset ajot estetty ja pyynnöillä lyhyet
  connect/total-timeoutit. Tarkista julkinen HTTPS-osoite, oikea 200 ja JSON status=UP.
- Tarkista myös etusivun odotettu sisältö harvemmin. Ei LLM-kutsuja, chat-viestejä,
  quota-kulutusta tai käyttäjätietojen lataamista.
- Kaksi peräkkäistä epäonnistumista laukaisee yhden DOWN-ilmoituksen; ensimmäinen
  virhe tallennetaan heti. Palautumisesta yksi UP-ilmoitus, pitkälle vialle rajatut
  muistutukset. Tavoite ensimmäisestä virheestä ilmoitukseen alle 90 sekuntia,
  ei toimitustakuu.
- Puhelimeen hyväksytty maksuton push-kanava (esimerkiksi Telegram) ja sähköposti.
  Omistaja valitsee kanavan ja testaa puhelimen oikean ilmoituksen, myös lukittuna.
- Syncille oma tarkistus olemassa olevan token-suojatun external-health-rajapinnan
  kautta: HTTP UP ei tarkoita toimivia tausta-ajoja. Tarkista nykyisen masterin
  rajapinta ja mode-semanttiikka ennen käyttöönottoa; OFF ei saa aiheuttaa väärää
  hälytystä, eikä token saa päästä lokiin.
- Ensimmäinen versio on vain lukeva: ei Fly-kirjoitustokenia eikä automaattisia
  restartteja joka HTTP-virheestä. DB-katkoa ei hoideta restart-myrskyllä.

Hyväksyntä: stub-testeillä timeout, DNS/TLS-virhe, 503, virheellinen JSON,
peräkkäiset virheet, palautuminen ja päällekkäisyys; oikea päästä päähän
puhelinhälytys erillisellä testikohteella katkaisematta tuotantoa.

### 3. Valvo myös valvojaa

Lähetä VPS-tarkistimen onnistuneesta suorituksesta minuutin välein heartbeat
Healthchecks.io:n Hobbyist-ilmaistasolle (tarkistettu: $0/kk, 20 jobia).
Tämä heartbeat kertoo tarkistimen elossaolosta, ei sovelluksen terveydestä:
myös havaittu DOWN + käsitelty hälytys on onnistunut tarkistusajo. Tarkistimen
pysähtyminen hälyttää erillistä reittiä noin 2–3 minuutissa. Valitse ilmaiset
integraatiot, älä SMS-/puhelupalvelua tai maksullista tilausta.

Hyväksyntä: tarkistin pysäytetään testi-ikkunassa, riippumaton dead-man-hälytys
saapuu; palautumisviesti saapuu uudelleenkäynnistyksen jälkeen. Ilmoitusreitit ja
palvelun minimiajastukset tarkistetaan käyttöönotossa, ei oleteta SLA:ta.

### 4. Kerää seuraavan katkon syy ja dokumentoi palautus

Ensimmäisen DOWN-havainnon yhteydessä tallenna rajattu, salaisuuksista puhdistettu
Machines events/config-yhteenveto, konekohtainen lokihäntä ja HTTP-tulos VPS:lle.
Vain tähän appiin rajattu lukuoikeus, jos Fly tarjoaa tarvittavan token-skoopin;
ei deploy-tokenia vain lokien takia. Ellei oikeuksia voi rajata riittävästi,
aloita HTTP-näytöstä ja manuaalisesta Fly-tallennuksesta. Säilytys enintään 14 vrk,
tiukka kokoraja ja oikeudet; ei täysiä ympäristömuuttujia tai chat-sisältöä.

Runbook: tallenna näyttö → erottele stopped/crash-loop, started mutta unhealthy,
DB-vika ja Fly-host/aluevika → käynnistä tarvittaessa sama kone omistajan toimesta
→ tarkista HTTP ja sync → kirjaa palautumisaika. Älä automaattisesti kloonaa konetta
äläkä ohita syncin epävarman lopputuloksen turvarajoja. Jos alkuperäisloki osoittaa
esimerkiksi ohimenevän käynnistysriippuvuuden, tee sille erillinen testattu korjaus.

## Kustannusraami ja toteutusjärjestys

1. Ota vain lukeva hälytys ja valvojan heartbeat käyttöön ensin.
2. Hyväksy restart-muutos PR:n kautta; omistaja yhdistää ja tavallinen deploy julkaisee.
3. Hyväksy toisen koneen hallittu poisto erikseen sync-omistajuuden varmistamisen jälkeen.
4. Testaa ilmoitukset ja palautuminen; kirjaa mitatut ajat, älä tavoiteaikoja tuloksiksi.

Yksi nykyinen Fly-kone, jo maksettu VPS ja ilmainen heartbeat/push: ei uusia
kiinteitä palvelumaksuja. Kustannusneutraalius edellyttää, että VPS on jatkuvasti
käytössä ja pienet HTTPS-pyynnöt mahtuvat nykyisiin liikennekiintiöihin. Tarkista
ensimmäisen viikon mitattu liikenne ja laskutus; rajaa lokimäärä. Maksullista
HA-replikaa, muistikorotusta, SMS:ää tai jatkuvaa GitHub Actions -pollingia ei lisätä.
VPS:n asennustilaa tai nykyisiä kolmannen osapuolen monitoreita ei tässä tarkistettu.

Toteutuksen kosketuspinta: fly.toml, pieni monitoriskripti ja systemd-yksiköt,
stub-testit sekä operointiohje. Käytä tuoretta feature branchia; tämän checkoutin
käyttäjän keskeneräisiä sync-muutoksia ei sisällytetä. Ei sovellusmuutoksia ilman
virhelokin osoittamaa tarvetta. Ei commitia/pushia tai tuotantomuutoksia tässä selvityksessä.

## Lähteet

- Fly Machines API: GET /v1/apps/fpl-assistant/machines/8151d9a97e7538/events
- https://fly.io/docs/machines/guides-examples/machine-restart-policy/
- https://fly.io/docs/reference/health-checks/
- https://fly.io/docs/reference/configuration/
- https://healthchecks.io/pricing/
- GitHub: .github/workflows/owned-sync-heartbeat.yml, ajohistoria ja repository-muuttujat
