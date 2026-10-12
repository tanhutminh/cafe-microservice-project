import { TestRequest } from '@angular/common/http/testing';

/**
 * Fails `req` the way a real backend would: status 0 is a network failure with no response,
 * delivered as a ProgressEvent; any other status is an error response.
 */
export function failWithStatus(req: TestRequest, status: number): void {
  if (status === 0) {
    req.error(new ProgressEvent('error'));
  } else {
    req.flush(null, { status, statusText: 'Error' });
  }
}
