# Saddle debugger smoke-test entry point
scoreboard objectives add saddle_dbg dummy
say [saddle-test] main start
scoreboard players set target saddle_dbg 42
function saddle_test:sub
scoreboard players add target saddle_dbg 1
say [saddle-test] main end
