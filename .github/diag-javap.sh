#!/bin/bash
# diagnostic only (branch diag-*): finds every place in the game code that reads the knockback resistance attributes
OUT=run/javap-knockback.txt
: > $OUT
JARS=$(find ~/.gradle ~/.cache -name '*.jar' -size +3M 2>/dev/null | while read j; do
  unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/level/ServerExplosion.class" && echo "$j"
done)
echo "jars with the server explosion: $JARS" >> $OUT
CJARS=$(find ~/.gradle ~/.cache -name '*.jar' -size +3M 2>/dev/null | while read j; do
  unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/multiplayer/ClientPacketListener.class" && echo "$j"
done)
echo "jars with the client packet listener: $CJARS" >> $OUT
for J in $(echo $JARS $CJARS | tr ' ' '\n' | sort -u); do
  D=/tmp/ex-$(basename "$J" .jar | tr -c 'A-Za-z0-9\n' '_')
  rm -rf "$D"; mkdir -p "$D"; (cd "$D" && unzip -q -o "$J" 'net/minecraft/*' 2>/dev/null)
  echo "===== $J" >> $OUT
  for F in $(cd "$D" && grep -rl "KNOCKBACK_RESISTANCE" net 2>/dev/null); do
    C=$(echo "$F" | sed 's/\.class$//; s#/#.#g')
    echo "--- class $C" >> $OUT
    javap -c -p -classpath "$D" "$C" 2>/dev/null | awk '
      /^  [a-zA-Z].*\(.*\)/ { m=$0 }
      /KNOCKBACK_RESISTANCE/ { print "   in " m "  ->  " $0 }' | sort -u >> $OUT
  done
  echo "--- handleExplosion in the client" >> $OUT
  javap -c -p -classpath "$D" net.minecraft.client.multiplayer.ClientPacketListener 2>/dev/null | awk '/handleExplosion\(/{p=1} p{print} p&&/^$/{exit}' >> $OUT
  echo "--- LocalPlayer / Entity push and addDeltaMovement" >> $OUT
  for C in net.minecraft.world.entity.Entity net.minecraft.world.entity.LivingEntity net.minecraft.client.player.LocalPlayer net.minecraft.world.entity.player.Player; do
    javap -c -p -classpath "$D" "$C" 2>/dev/null | awk -v c="$C" '/^  [a-zA-Z].*(push|addDeltaMovement|knockback|setDeltaMovement)\(/{p=1; print "## " c ": " $0} p&&/^$/{p=0} p&&/invoke|getstatic/{print}' >> $OUT
  done
done
wc -l $OUT
