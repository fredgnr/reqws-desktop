import type { ReqwsAPI } from '../shared/types';

declare global {
  interface Window {
    reqws: ReqwsAPI;
  }
}

export {};
