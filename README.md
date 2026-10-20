# Sword Bot (Fabric 1.21.1)

Un « Traqueur » (zombie avec casque noir et épée) poursuit les joueurs. Mod côté serveur : les clients vanilla se connectent sans rien installer.

## Compiler
Java 21 requis.

    ./gradlew build        (Windows : gradlew.bat build)

Le mod est dans `build/libs/swordbot-1.0.0.jar` (ignore le `-sources`). Mets-le dans `mods/` du serveur avec le Fabric API 0.116.17+1.21.1.

## Progression des épées
| Succès | Délai | Épée |
|---|---|---|
| (départ) | - | bois |
| Stone Age | 20 s | pierre |
| Isn't It Iron Pick | 30 s | fer |
| Cover Me with Diamonds | 1 min | diamant |
| Cover Me in Debris | 0 s | netherite |

Dès qu'un seul joueur a le succès, le bot s'améliore (pour tout le serveur). La progression est sauvegardée dans le monde.

## Sécurité du bot
- Insensible à la lave, au feu, aux chutes, au vide et à la noyade.
- Avant de tomber dans un gros trou ou dans la lave, il est soulevé, devient invulnérable, traverse lentement au-dessus du danger, puis se pose près d'un joueur.
- S'il reste bloqué 6 s loin d'un joueur, il fait la même chose. Trop loin (> 48 blocs) : il réapparaît plus près.

## Réglages
Tout est en haut de `src/main/java/fr/swordbot/ChaserManager.java` (délais, vitesse, hauteur de vol...).

## Commandes utiles
- Supprimer le bot : `/kill @e[tag=swordbot_chaser]` (il réapparaît après 10 s).
- Donner un succès pour tester : `/advancement grant @s only minecraft:story/mine_stone`
