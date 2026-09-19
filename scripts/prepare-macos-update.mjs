#!/usr/bin/env node
import { prepareMacosUpdate } from './prepare-macos-update.mts';

prepareMacosUpdate(process.argv.slice(2)).catch((error) => {
  console.error(error instanceof Error ? error.message : 'Update metadata validation failed.');
  process.exitCode = 1;
});
