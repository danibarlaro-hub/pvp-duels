#!/bin/bash
# Prints real class signatures and vanilla data from the Minecraft/Fabric jars used by the build.
OUT=inspect-out
rm -rf $OUT; mkdir -p $OUT/javap $OUT/data
find / -name "*.jar" -size +100k 2>/dev/null | grep -v -E "^/(proc|sys|usr/lib/jvm|opt/hostedtoolcache)" > $OUT/all-jars.txt
grep -i -E "minecraft|fabric-api|fabric-.*-v[0-9]|fabric-loader|brigadier|datafixer|mojang|netty-buffer" $OUT/all-jars.txt | grep -v -E "sources|javadoc" > $OUT/jars.txt
# Fabric API is a jar-in-jar bundle: unpack the nested modules so javap can see them
mkdir -p /tmp/fabnested
for j in $(grep -E "/fabric-api-[0-9]" $OUT/jars.txt); do unzip -q -o "$j" 'META-INF/jars/*.jar' -d /tmp/fabnested 2>/dev/null; done
ls /tmp/fabnested/META-INF/jars 2>/dev/null > $OUT/fabric-modules.txt
CP=$(tr '\n' ':' < $OUT/jars.txt)$(ls /tmp/fabnested/META-INF/jars/*.jar 2>/dev/null | tr '\n' ':')
echo "$CP" > $OUT/classpath.txt

# Main Minecraft jar = the biggest jar that contains net/minecraft/server/MinecraftServer.class
MC=""
while read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/server/MinecraftServer.class"; then MC="$j"; echo "MC jar: $j" >> $OUT/info.txt; break; fi
done < <(xargs -a $OUT/jars.txt ls -S 2>/dev/null)

if [ -n "$MC" ]; then
  unzip -l "$MC" | awk '{print $4}' | grep -E "\.class$" | sed 's/\.class$//' | tr '/' '.' | sort > $OUT/mc-classes.txt
  mkdir -p /tmp/mcdata
  unzip -q -o "$MC" 'data/minecraft/dimension_type/*' 'data/minecraft/worldgen/world_preset/*' 'data/minecraft/worldgen/flat_level_generator_preset/*' 'data/minecraft/dimension/*' 'data/minecraft/worldgen/biome/the_void.json' 'data/minecraft/tags/worldgen/flat_level_generator_preset/*' -d /tmp/mcdata 2>> $OUT/info.txt
  cp -r /tmp/mcdata/data $OUT/data/ 2>/dev/null
  unzip -l "$MC" | awk '{print $4}' | grep -E "^data/minecraft/(dimension|dimension_type|worldgen/world_preset|worldgen/flat)" > $OUT/data-listing.txt
  # Every block that has a loot table = (almost) every block id of the game, including the colored ones
  unzip -l "$MC" | awk '{print $4}' | grep -E "^data/minecraft/loot_table/blocks/.*\.json$" | sed 's#.*/##; s#\.json$##' | sort > $OUT/block-ids.txt
fi

while read -r c; do
  [ -z "$c" ] && continue
  javap -p -classpath "$CP" "$c" > "$OUT/javap/$c.txt" 2>&1
done < inspect/classes.txt

# Extra: javap of anything named in inspect/extra.txt (nested classes use $)
[ -f inspect/extra.txt ] && while read -r c; do [ -n "$c" ] && javap -p -classpath "$CP" "$c" > "$OUT/javap/$c.txt" 2>&1; done < inspect/extra.txt

# Optional grep over class names: inspect/find.txt has one regex per line
if [ -f inspect/find.txt ]; then
  while read -r rx; do [ -n "$rx" ] && { echo "## $rx"; grep -E "$rx" $OUT/mc-classes.txt; } ; done < inspect/find.txt > $OUT/find-result.txt
fi
# Bytecode of a few methods (to see what vanilla really does)
if [ -f inspect/code.txt ]; then
  mkdir -p $OUT/code
  while read -r c; do [ -n "$c" ] && javap -p -c -classpath "$CP" "$c" > "$OUT/code/$c.txt" 2>&1; done < inspect/code.txt
fi

ls -R $OUT | head -5 >> $OUT/info.txt
