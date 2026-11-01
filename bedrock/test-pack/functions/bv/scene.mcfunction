# Generated from TestScene.java by TestSceneTest: builds the Bedrock Visuals test scene north of you and
# points the camera at it, the same as /bv scene in Java. Stops the clock, weather and mob spawning.
gamerule dodaylightcycle false
gamerule doweathercycle false
gamerule domobspawning false
weather clear
time set 6000
kill @e[type=!player,r=64]
fill ~-6 ~ ~ ~6 ~9 ~-20 air
fill ~-6 ~-1 ~ ~6 ~-1 ~-20 grass_block
setblock ~-5 ~ ~-10 iron_block
setblock ~-4 ~ ~-10 gold_block
setblock ~-3 ~ ~-10 copper_block
setblock ~-2 ~ ~-10 diamond_block
setblock ~-1 ~ ~-10 emerald_block
setblock ~ ~ ~-10 glowstone
setblock ~1 ~ ~-10 sea_lantern
setblock ~2 ~ ~-10 glass
setblock ~3 ~ ~-10 oak_leaves ["persistent_bit"=true]
setblock ~4 ~ ~-10 polished_andesite
setblock ~5 ~ ~-10 oak_planks
fill ~-6 ~-1 ~-14 ~-4 ~-1 ~-17 water
fill ~-2 ~ ~-15 ~-2 ~5 ~-15 stone_bricks
setblock ~-1 ~ ~-14 torch
fill ~1 ~ ~-16 ~1 ~2 ~-16 oak_log
fill ~3 ~ ~-16 ~3 ~2 ~-16 oak_log
fill ~ ~3 ~-14 ~4 ~3 ~-18 oak_planks
fill ~4 ~4 ~-18 ~6 ~6 ~-20 oak_leaves ["persistent_bit"=true]
fill ~5 ~ ~-19 ~5 ~5 ~-19 oak_log
execute align xyz run tp @s ~0.5 ~ ~0.5 180 15
