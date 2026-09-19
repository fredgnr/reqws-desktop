#!/usr/bin/env node
import { cleanupMacosSigning, withMacosSigning } from './with-macos-signing.mts';

const args = process.argv.slice(2);
const operation = args.length === 1 && args[0] === '--cleanup'
  ? cleanupMacosSigning()
  : args[0] === '--'
    ? withMacosSigning(args.slice(1))
    : Promise.reject(new Error('Usage: with-macos-signing.mjs -- COMMAND [ARGS] | --cleanup'));
operation.catch(() => {
  // Do not print arbitrary subprocess errors in a process holding credentials.
  console.error('macOS signing failed. Check the protected job setup and cleanup step.');
  process.exitCode = 1;
});
