# Verhaeghe Irrigation – application Android

Application de terrain : dépannage des enrouleurs (Irrifrance, RM, Bauer…), stations de pompage (mise en service, diagnostic, forages GPS), mémo pièces, magasin de références, heures et photos.

## Récupérer l'APK

À chaque modification poussée sur `main`, GitHub construit l'APK automatiquement (onglet **Actions**) et le publie dans la release **« derniere-version »** :

1. Sur le téléphone Android, ouvrez la page **Releases** du dépôt.
2. Téléchargez `Verhaeghe-Irrigation.apk`.
3. Ouvrez le fichier ; autorisez l'installation depuis cette source si Android le demande.

## Fonctionnement

- L'application est la même page que la version web (`app/src/main/assets/index.html`), affichée dans une WebView.
- Les données (mémo, heures, forages, magasin, photos, fiches) sont enregistrées **sur le téléphone**, sans connexion nécessaire.
- Le PDF du mémo s'enregistre dans **Téléchargements** puis la feuille de partage Android s'ouvre (WhatsApp, e-mail…).
- Itinéraire, cartes, WhatsApp, e-mail et météo s'ouvrent dans les applications du téléphone.
- Non disponibles dans l'APK (fonctions liées à Claude) : synchronisation entre appareils, assistant, relevé météo automatique, PDF de notices partagés.

## Mettre à jour

Dans l'appli : **Paramètres** (roue dentée en haut à droite) → **Rechercher une mise à jour**. L'appli lit `version.json` et propose le nouvel APK s'il existe.

Pour publier une version : remplacez `app/src/main/assets/index.html`, augmentez `versionCode`/`versionName` dans `app/build.gradle`, mettez les mêmes valeurs dans `version.json` (et `APP_VERSION`/`APP_CODE` dans `index.html`), puis poussez sur `main`.
