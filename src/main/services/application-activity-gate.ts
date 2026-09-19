import { ReqwsError } from '../../shared/errors';

/** Admission happens synchronously, before any await or queue insertion. */
export class ApplicationActivityGate {
  private active = 0;
  private shuttingDown = false;

  enter(): () => void {
    if (this.shuttingDown) throw this.busy();
    this.active += 1;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.active -= 1;
    };
  }

  acquireShutdown(): () => void {
    if (this.shuttingDown || this.active !== 0) throw this.busy();
    this.shuttingDown = true;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.shuttingDown = false;
    };
  }

  private busy() {
    return new ReqwsError({ code: 'UPDATE_BUSY', message: 'Application operations or update installation are in progress.' });
  }
}
