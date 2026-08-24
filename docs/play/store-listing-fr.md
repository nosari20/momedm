# Fiche de présentation — Français (fr-FR)

À coller dans Play Console → **Développement → Présence sur le Store → Fiche
Store principale**, langue Français (France). Comptages mesurés avec `len()`
en Python sur le texte exact (compte les points de code Unicode, comme Play
Console), pas avec `wc`, car le texte contient des apostrophes typographiques,
un tiret cadratin et des guillemets français.

Cette traduction est une vraie traduction, adaptée au ton du reste de
l'appli (vocabulaire déjà utilisé dans `values-fr/strings.xml` : « Mode
enfant », « Restrictions de contenu », « Accès à l'usage », « Code PIN
parental », « Verrouillage la nuit ») — pas une traduction mot à mot du
texte anglais.

## Nom de l'appli

Limite : 30 caractères.

```
Môme DM : Contrôle parental
```

**27 / 30 caractères.**

Le libellé affiché sur l'appareil (`app_name` dans `strings.xml`) est
simplement « Môme DM » ; ce titre de fiche ajoute « Contrôle parental »
uniquement pour la recherche et la clarté sur la page Store — ce n'est pas
une allégation que l'appli fait ailleurs dans son interface.

## Description courte

Limite : 80 caractères.

```
Contrôle parental sans cloud : deux téléphones, un lien Bluetooth.
```

**66 / 80 caractères.**

## Description complète

Limite : 4000 caractères.

```
Môme DM est une appli de contrôle parental pour toute la famille, construite autour d'une idée simple : elle continue de fonctionner même quand votre téléphone n'est pas à proximité.

La plupart des outils de contrôle parental font transiter l'activité de l'enfant par les serveurs d'une entreprise. Môme DM ne le fait pas. Une seule appli, sur deux téléphones, qui se parlent en Bluetooth — rien d'autre. Pas de cloud, pas de compte, pas d'abonnement, pas de publicité, aucun suivi ni collecte de données.

COMMENT ÇA MARCHE

Installez Môme DM chez vous et chez l'enfant. Chez l'enfant, l'appli est mise en place lors d'une réinitialisation d'usine, comme propriétaire de l'appareil, et devient l'écran d'accueil. Chez vous, c'est un panneau de contrôle. Ensuite, les deux téléphones ne communiquent plus que par Bluetooth, à portée l'un de l'autre. Le Wi-Fi n'intervient qu'une fois, pour l'installation chez l'enfant.

Les règles vivant chez l'enfant, elles s'appliquent même hors de portée, téléphone éteint ou déchargé : une télécommande, pas une dépendance.

CE QUE VOUS POUVEZ FAIRE

• Choisir les applis autorisées. Les autres n'apparaissent pas sur l'écran d'accueil et ne peuvent pas être lancées. Possibilité de limiter le téléphone à une seule appli.

• Régler les restrictions de contenu. Trois niveaux — désactivé, modéré, strict — activent SafeSearch, la navigation sécurisée de Chrome et le mode restreint de YouTube, plus en option un filtrage DNS familial appliqué à tout le téléphone, pas juste à un navigateur. L'appli explique ce que chaque réglage couvre, et ce qu'il ne couvre pas.

• Configurer d'autres applis. Beaucoup déclarent leurs propres réglages administrables (Chrome en déclare des centaines). Môme DM lit ce qu'une appli déclare et construit un formulaire pour la régler — rien n'est codé en dur.

• Fixer une heure de coucher. Une plage pour les nuits d'école, une autre pour le week-end : le téléphone se verrouille complètement — plus d'appli, juste une horloge — et se rouvre à l'heure prévue.

• Utiliser un code PIN pour les exceptions. Le taper chez l'enfant met les restrictions en pause dix minutes, puis elles reprennent seules. Le code est haché avant de quitter votre téléphone.

• Voir ce qui se passe. Applis autorisées, appli en cours d'usage (si l'accès à l'usage a été accordé), batterie, état du verrouillage, et un journal de la connexion Bluetooth pour que les soucis d'appairage ne passent pas inaperçus.

• Adapter l'apparence. Thème, couleur et langue (français et anglais, appli bilingue) se règlent chez vous et se transmettent chez l'enfant.

CE QUE CETTE APPLI N'EST PAS

Une aide pour les parents, pas un produit de sécurité. Une réinitialisation d'usine la supprime, et un adolescent déterminé finira par en trouver les limites. Pensée pour le cas courant — s'accorder en famille sur des règles et les faire respecter — pas pour déjouer quelqu'un qui cherche à la contourner. Tout ce que l'appli fait chez l'enfant y est visible : c'est l'écran d'accueil, avec un bandeau « Mode enfant », sans mode caché ni déguisé. Un outil familial, utilisé ouvertement — pas de surveillance discrète.

CE QU'IL VOUS FAUT

Deux téléphones Android 14 ou plus récent. Le téléphone de l'enfant doit être réinitialisé pour être configuré, car Android n'accorde les droits nécessaires qu'à la configuration initiale. Bluetooth requis des deux côtés ; ensuite, aucun internet, compte Google ni Play Services n'est nécessaire au quotidien.

CE QUI N'EST PAS INCLUS

Pas d'installation silencieuse : installer une appli ouvre sa fiche Play Store pour appuyer sur Installer. Pas de suivi de localisation, pas de surveillance des messages, des appels ou de l'historique de navigation, pas d'accès distant à la caméra ou au micro. Si une fonction n'est pas listée ci-dessus, l'appli ne la fait pas.

Open source, licence Apache 2.0. Code source complet, y compris le détail exact des échanges entre les deux téléphones, sur github.com/nosari20/momedm.
```

**3990 / 4000 caractères.**

## Notes pour la personne qui colle ce texte

- Conservez le caractère de puce `•` tel quel ; l'éditeur de fiche Play
  l'accepte et l'affiche correctement.
- Les guillemets « français » et l'apostrophe typographique sont
  intentionnels — cohérents avec le reste des textes de l'appli
  (`values-fr/strings.xml`).
- Si le compteur de caractères de Play Console affiche un chiffre légèrement
  différent de celui indiqué ici, faites confiance au compteur de Play
  Console au moment de la soumission et raccourcissez d'abord le dernier
  paragraphe.
