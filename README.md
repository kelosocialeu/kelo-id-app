# Kelo ID App

Application Android dédiée à Kelo ID.

## Objectif

Ce dépôt reprend le moteur NFC Android déjà présent dans `kelosocialeu/keloid/native/android`, afin de conserver la vérification belge qui fonctionne déjà, puis d'ajouter progressivement les lecteurs par pays.

### Belgique

La vérification belge réutilise le lecteur ICAO/eMRTD actuel, la validation DG1/SOD, PACE/BAC et la chaîne de confiance CSCA belge.

### France

La CNIe française au format carte bancaire sera prise en charge via NFC/PACE. Le CAN est prévu dans le modèle de credentials. La lecture française ne devra jamais valider définitivement une identité tant que la chaîne de confiance française (DSC/CSCA) n'est pas configurée et testée avec des cartes réelles.

## Sécurité

- aucune donnée d'identité brute n'est persistée par le module NFC ;
- les preuves sont signées avec une clé P-256 Android Keystore ;
- les cartes inconnues sont refusées ;
- une vérification ne doit être acceptée côté backend que si l'intégrité du document, le canal NFC et la confiance émetteur sont validés.

## Développement

- JDK 17
- Android SDK 35
- Android Studio récent

Le backend reste Kelo ID/Supabase. L'application mobile ne doit jamais embarquer de clé Supabase `service_role`.