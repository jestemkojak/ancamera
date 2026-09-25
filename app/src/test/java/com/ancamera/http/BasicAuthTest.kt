package com.ancamera.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicAuthTest {
    // "user:pa:ss" in Base64. A colon in the password must work.
    private val good = "Basic dXNlcjpwYTpzcw=="

    @Test fun offWhenNoCredentials() = assertTrue(BasicAuth.isAuthorized(null, "", ""))
    @Test fun acceptsRightCredentials() = assertTrue(BasicAuth.isAuthorized(good, "user", "pa:ss"))
    @Test fun schemeIsCaseInsensitive() = assertTrue(BasicAuth.isAuthorized("basic dXNlcjpwYTpzcw==", "user", "pa:ss"))
    @Test fun rejectsMissingHeader() = assertFalse(BasicAuth.isAuthorized(null, "user", "pa:ss"))
    @Test fun rejectsWrongPassword() = assertFalse(BasicAuth.isAuthorized(good, "user", "other"))
    @Test fun rejectsWrongUser() = assertFalse(BasicAuth.isAuthorized(good, "admin", "pa:ss"))
    @Test fun rejectsBadBase64() = assertFalse(BasicAuth.isAuthorized("Basic !!!", "user", "pa:ss"))
    @Test fun rejectsOtherScheme() = assertFalse(BasicAuth.isAuthorized("Bearer dXNlcjpwYTpzcw==", "user", "pa:ss"))
    // "nocolon" in Base64
    @Test fun rejectsNoColon() = assertFalse(BasicAuth.isAuthorized("Basic bm9jb2xvbg==", "user", "pa:ss"))
}
