# Politique de confidentialité — Môme DM

*Date d'effet : 26 août 2026. Mise à jour à chaque modification du texte ;
l'historique complet est visible dans le journal git public du projet.*

Môme DM (« l'appli ») est développée par Florent NOSARI. Cette politique couvre
l'application Android d'identifiant `edu.fnosari.momedm`, distribuée sur
Google Play et depuis le dépôt source github.com/nosari20/momedm.

## L'essentiel

Môme DM n'a pas de serveur. Elle n'a pas de système de compte. Elle ne
collecte, ne stocke, ne transmet, ne vend ni ne partage aucune donnée
personnelle avec le développeur ou avec un tiers — parce qu'il n'existe aucun
canal par lequel ces données pourraient voyager, en dehors d'une connexion
Bluetooth Low Energy (BLE) directe entre le téléphone d'un parent et celui
d'un enfant, mise en place par le parent lui-même. Rien de ce que votre
famille fait dans l'appli n'est visible du développeur, de Google (au-delà de
la distribution Play Store et des diagnostics standard que traverse toute
appli), ni de qui que ce soit d'autre.

## Les données que l'appli manipule, et où elles restent

Môme DM fonctionne dans l'un de deux rôles, choisi automatiquement :

- **Contrôleur** (le téléphone du parent) : un petit panneau de contrôle.
- **Géré** (le téléphone de l'enfant) : propriétaire de l'appareil et écran
  d'accueil.

Les deux rôles échangent les éléments suivants par une connexion BLE directe,
authentifiée, de point à point — jamais par internet, et jamais par un
serveur que le développeur opérerait, puisqu'un tel serveur n'existe pas :

- La liste des applis installées sur le téléphone de l'enfant, et celles que
  le parent a autorisées.
- La présence en ligne/hors ligne de l'appareil enfant, son niveau de
  batterie et (seulement si l'autorisation facultative d'accès à l'usage a
  été accordée pendant l'installation) le nom de paquet de l'appli au premier
  plan.
- Le planning de verrouillage nocturne et l'état de verrouillage.
- Un **hachage** du code PIN parental (PBKDF2-HMAC-SHA256, 20 000
  itérations, salé) — le PIN en clair n'est jamais transmis ni stocké sur le
  téléphone de l'enfant.
- Les préférences d'affichage : langue, thème et couleur.
- Un secret d'association à usage unique, généré sur le téléphone du parent
  pendant l'installation et affiché en QR code, qui authentifie le lien BLE.

Aucune de ces données ne quitte la paire d'appareils qu'elle concerne. Rien
n'est envoyé au développeur, à un service d'analyse ou de publicité, ni à un
stockage cloud — l'appli n'a aucun code réseau qui parle à autre chose que le
point d'accès Wi-Fi local (pour le téléchargement unique de l'appli à
l'installation) et les écrans Play Store / compte Google que l'utilisateur
ouvre explicitement.

## Où les données sont stockées

Toutes les données ci-dessus sont stockées **localement sur les deux
téléphones**, dans le stockage privé de l'appli (Jetpack DataStore), protégé
par le cloisonnement normal des applis d'Android. Pas de sauvegarde cloud :
l'appli déclare `android:allowBackup="false"`, ses données ne figurent donc
pas non plus dans les sauvegardes automatiques d'Android.

## Les autorisations demandées, et pourquoi

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` : le lien BLE
  entre les deux téléphones. `BLUETOOTH_SCAN` est demandée avec
  `neverForLocation` : Android ne la traite pas comme une autorisation de
  localisation et aucune donnée de localisation n'en est tirée.
- `NEARBY_WIFI_DEVICES` : la mise en place du point d'accès local du
  téléphone parent pour le transfert unique de l'appli. Également demandée
  avec `neverForLocation`.
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE` : le
  point d'accès local / serveur HTTP qui fournit l'appli au téléphone de
  l'enfant à l'installation, côté parent uniquement.
- `ACCESS_LOCAL_NETWORK` : exigée par Android 16+ pour que le téléphone du
  parent puisse servir l'appli sur le réseau local pendant l'installation.
- `INTERNET` : nécessaire au serveur HTTP local, et à l'ouverture des fiches
  Play Store pour installer des applis. L'appli ne fait aucune autre requête
  réseau.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `POST_NOTIFICATIONS` : maintenir le lien BLE quand l'appli n'est pas au
  premier plan, avec une notification permanente et visible sur les deux
  téléphones — jamais cachée, ni au parent ni à l'enfant.
- `CAMERA` : scanner un QR code, uniquement pour ré-associer un téléphone
  enfant déjà configuré à un (nouveau) téléphone parent.
- `RECEIVE_BOOT_COMPLETED` : redémarrer le lien et réévaluer le planning de
  verrouillage après un redémarrage du téléphone de l'enfant.
- `PACKAGE_USAGE_STATS` : facultative. Si le parent l'accorde à
  l'installation, le téléphone de l'enfant peut indiquer l'appli au premier
  plan. Ignorable — rien ne casse sans elle, la ligne reste simplement vide.
- `SCHEDULE_EXACT_ALARM` : réveiller le téléphone de l'enfant à la bonne
  minute pour démarrer ou terminer le verrouillage du coucher.

Chacune de ces autorisations ne sert qu'à faire fonctionner la connexion
directe entre les deux téléphones, ou à laisser le téléphone de l'enfant
agir comme propriétaire d'appareil, décrit ci-dessous. Aucune ne sert à
collecter des données pour le développeur.

## Propriétaire d'appareil

Sur le téléphone de l'enfant, Môme DM est installée comme **propriétaire de
l'appareil** Android (via `DevicePolicyManager`, après une réinitialisation
d'usine). C'est ce qui lui permet de devenir l'écran d'accueil, de
restreindre les applis lançables (mode « lock task » d'Android) et de
verrouiller complètement le téléphone selon un planning. Être propriétaire
de l'appareil donne à l'appli un contrôle étendu du téléphone de l'enfant —
c'est la fonctionnalité — mais cela ne donne ni à l'appli ni au développeur
un accès à distance à quoi que ce soit au-delà de ce que décrit cette
politique. Pas de canal de support à distance, pas d'accès distant à
l'écran, pas de serveur par lequel le développeur pourrait atteindre les
appareils d'une famille.

## Ce que l'appli ne fait pas

- Pas de SDK d'analyse, de rapport de plantage ni de publicité.
- Pas de compte utilisateur — rien où se connecter, rien à supprimer,
  puisque rien n'est stocké ailleurs que sur les deux téléphones.
- Pas de vente ni de partage de données avec des tiers, puisqu'aucune donnée
  n'est collectée de façon centralisée.
- Pas de suivi de l'enfant au-delà des fonctions décrites dans l'appli et
  dans le README public du projet, qui précise exactement ce qui est visible
  du parent et ce qui ne l'est pas.
- Rien n'est visible du téléphone de l'enfant quand les deux téléphones sont
  hors de portée Bluetooth l'un de l'autre, et rien n'est mis en file pour
  plus tard — chaque commande est délivrée en direct par BLE, ou pas du tout.

## Suppression des données

Puisque rien n'est stocké hors des appareils, il n'y a rien que le
développeur puisse supprimer sur demande. Pour effacer toutes les données de
l'appli :

- **Sur le téléphone du parent :** désinstallez l'appli, ou utilisez
  Réglages → Avancé → Nouvelle clé d'association, puis désinstallez.
- **Sur le téléphone de l'enfant :** une réinitialisation d'usine supprime
  l'appli et toutes ses données (propriétaire de l'appareil, elle n'est pas
  désinstallable par le geste ordinaire). Le téléphone peut aussi être
  désenrôlé par les mécanismes standard de retrait du propriétaire
  d'appareil, là où le constructeur le permet.

## Open source

Le code source complet de Môme DM est public sous licence Apache-2.0 à
github.com/nosari20/momedm. Chacun — pas seulement les équipes de
vérification de Google — peut lire exactement ce que l'appli fait des
autorisations et données décrites ici ; rien ne repose sur la seule
confiance.

## Vie privée des enfants

Môme DM est installée et configurée par un parent, sur son propre téléphone
et sur un téléphone qu'il possède et réinitialise pour l'enfant. L'appli ne
collecte aucune information personnelle de l'enfant pour l'usage du
développeur ; les seules données manipulées sont la configuration de la
famille elle-même, échangée directement entre les deux téléphones que le
parent contrôle, comme décrit ci-dessus.

## Modifications de cette politique

Si cette politique change, la version à jour est publiée à la même adresse
et dans cette appli, et l'historique des versions est visible dans le
journal git du projet, puisque ce texte vit dans le dépôt source public de
l'appli.

## Contact

Florent NOSARI — nosari20@gmail.com — ou ouvrez un ticket à
github.com/nosari20/momedm/issues.
