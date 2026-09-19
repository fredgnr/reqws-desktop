#!/usr/bin/env node
import { cleanupMacosSigning, signingFailureMessage, withMacosSigning } from './with-macos-signing.mts';

const args = process.argv.slice(2);
const operation = args.length === 1 && args[0] === '--cleanup'
  ? cleanupMacosSigning()
  : args[0] === '--'
    ? withMacosSigning(args.slice(1))
    : Promise.reject(new Error('Usage: with-macos-signing.mjs -- COMMAND [ARGS] | --cleanup'));
operation.catch((error) => {
  // Do not print arbitrary subprocess errors in a process holding credentials.
  console.error(signingFailureMessage(error));
  process.exitCode = 1;
});
