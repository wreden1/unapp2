# Inte en chatt

Ett fristående Android-projekt för att isolera karaktärsväljaren från UnApp.

- Egen Android-app och eget applicationId: `se.notenchat.app`
- Ingen UnApp-kod, inget UnApp-minne och ingen delad konversationshistorik.
- Tre inbyggda testprofiler: Linda, Alex och Neutral.
- Karaktärsinstruktionen visas i gränssnittet så att det går att kontrollera vilken profil som är aktiv.

## Status

Första byggsteget innehåller appskal, karaktärsväljare och chattgränssnitt. AI-motorn är ännu inte ansluten. Det är avsiktligt: nästa steg är att välja och koppla en separat modellmotor utan att återanvända UnApps chattlogik.
