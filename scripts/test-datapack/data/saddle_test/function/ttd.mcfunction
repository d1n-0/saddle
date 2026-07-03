# storage time-travel test: two writes between recorded steps
data merge storage saddle_test:ttd {v: 1}
data merge storage saddle_test:ttd {v: 2}
say [saddle-test] ttd done
