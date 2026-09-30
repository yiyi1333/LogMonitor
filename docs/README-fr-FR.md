# LogMonitor

Version actuelle : **v1.1.4**

LogMonitor est une plateforme d'analyse de journaux destinée aux opérations. Elle collecte les logs Spring Boot locaux ou distants, regroupe les erreurs, agrège les tendances par espace de noms et peut appeler des fournisseurs LLM après masquage des données sensibles.

## Liens rapides

- README complet chinois/anglais : [README racine](../README.md)
- Architecture du service : [ARCHITECTURE.md](ARCHITECTURE.md)
- Architecture d'exécution : [RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- Publication et installation : [deploy/README.md](../deploy/README.md)

## Fonctionnalités

- Frontend Vue 3 + TypeScript, centre Spring Boot 3 / JDK 17 et Agent JDK 8.
- MySQL 8.0.36+ en production ; H2 mémoire pour la démonstration locale.
- Collecte locale/distante, rotation, déduplication et file disque de 5 Go.
- Agrégation par espace de noms, URI, instance et minute avec détail des erreurs.
- Masquage des tokens, cookies, mots de passe, téléphones, identifiants et IP avant le LLM.

## Démarrage local

Suivez [Quick Start](../README.md#quick-start) avec JDK 17 et Node.js 20.19+/22.12+. Pour enregistrer un Agent, consultez [l'installation de l'Agent](../deploy/README.md#安装-agent).

## Versionnement

Un commit de développement augmente patch de `+0.0.1`; une version officielle augmente minor de `+0.1.0` et remet patch à zéro. Les trois composants restent synchronisés.
