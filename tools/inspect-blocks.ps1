$b = Get-Content "C:\Temp\mcdata\blocks.json" -Raw | ConvertFrom-Json
$names = @("air","stone","water","oak_slab","oak_stairs","oak_fence","cobblestone_wall","glass_pane","oak_door","oak_trapdoor","white_carpet","snow","red_bed","ladder","scaffolding","slime_block","honey_block","soul_sand","powder_snow","bubble_column","lava","magma_block","ice","packed_ice","blue_ice","cobweb","vine","bamboo","cauldron","chest","soul_soil","sweet_berry_bush","hopper","anvil","water_cauldron","big_dripleaf","light","sculk_sensor","cactus","dirt_path","farmland","grindstone","lectern","stonecutter","enchanting_table","brewing_stand","bell","piston","sticky_piston","end_rod","lily_pad","mangrove_roots","sea_pickle","turtle_egg","candle","chain","lantern","campfire","flower_pot","tall_grass","kelp","seagrass","pointed_dripstone","hanging_roots","cave_vines","dirt_path","sugar_cane","chorus_flower","end_portal","pale_moss_carpet","sculk_vein","glow_lichen","resin_clump","leaf_litter","wildflowers","moss_carpet","honey_block","daylight_detector","dragon_egg","lily_pad")
foreach ($n in $names) {
  $e = $b | Where-Object { $_.name -eq $n }
  if ($e) {
    $bb = $e.boundingBox
    $st = ($e.states | ForEach-Object { $_.name }) -join ","
    Write-Output "=== $n bb=[$bb] states=[$st]"
  } else {
    Write-Output "MISSING $n"
  }
}
