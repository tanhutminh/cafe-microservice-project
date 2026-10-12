import { HttpContextToken } from '@angular/common/http';

/**
 * Marks a request whose sender handles a 401 response itself: errorInterceptor passes the error
 * on without clearing the session or redirecting to /login.
 */
export const HANDLES_UNAUTHORIZED = new HttpContextToken<boolean>(() => false);
