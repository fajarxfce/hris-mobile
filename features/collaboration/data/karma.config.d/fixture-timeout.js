// These correctness fixtures persist and traverse 500–600 encrypted records.
// Keep a finite runner budget that also permits execution on a loaded CI host.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 10000;
