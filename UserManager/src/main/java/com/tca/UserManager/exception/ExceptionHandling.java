package com.tca.UserManager.exception; 
 
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.web.bind.annotation.*; 
import org.slf4j.Logger; 
import org.slf4j.LoggerFactory; 
import org.springframework.http.*; 
import com.tca.UserManager.domain.HttpResponse; 
import com.tca.UserManager.exception.domain.*; 
import static org.springframework.http.HttpStatus.*; 
import com.auth0.jwt.exceptions.*; 
import org.springframework.web.bind.annotation.ExceptionHandler; 
import org.springframework.security.authentication.*; 
import org.springframework.security.access.AccessDeniedException; 
import org.springframework.security.core.AuthenticationException; 
import org.springframework.web.HttpRequestMethodNotSupportedException; 
import java.util.Objects; 
 
@RestController 
@RestControllerAdvice  // Catches exceptions from ALL controllers in one place 
public class ExceptionHandling implements ErrorController { 
 
    final Logger logger = LoggerFactory.getLogger(this.getClass()); 
 
    // Error message constants: 
    private static final String TOKEN_DECODE_ERROR    = "Token Decode Error"; 
    private static final String TOKEN_EXPIRED_ERROR   = "Token has Expired"; 
    private static final String ACCOUNT_LOCKED        = "Your account has been locked"; 
    private static final String INCORRECT_CREDENTIALS = "Username or Password is Incorrect"; 
    private static final String ACCOUNT_DISABLED      = "Your account has been disabled"; 
    private static final String NOT_ENOUGH_PERMISSION = "You do not have enough permission"; 
    private static final String NOT_AUTHENTICATED     = "You need to log in to access this URL"; 
    private static final String NO_MAPPING_URL        = "There is no mapping for this URL"; 
    private static final String INTERNAL_SERVER_ERR   = "An error occurred while processing the request"; 
    //private static final String METHOD_NOT_ALLOWED    = "This request method is not allowed: '%s'"; 
    private static final String ERROR_PATH            = "/error"; 
 
    // ── Helper: build a consistent HttpResponse ──────────────────────── 
    private ResponseEntity<HttpResponse> createHttpResponse(HttpStatus status, 
String msg) { 
        return new ResponseEntity<>( 
            new HttpResponse(status.value(), status, 
status.getReasonPhrase().toUpperCase(), msg), 
            status); 
    } 
 
    // ── Security exceptions ──────────────────────────────────────────── 
    @ExceptionHandler(JWTDecodeException.class) 
    public ResponseEntity<HttpResponse> tokenDecodeException() { 
        return createHttpResponse(BAD_REQUEST, TOKEN_DECODE_ERROR); 

    } 
 
    @ExceptionHandler(DisabledException.class) 
    public ResponseEntity<HttpResponse> accountDisabledException() { 
        return createHttpResponse(BAD_REQUEST, ACCOUNT_DISABLED); 
    } 
 
    @ExceptionHandler(BadCredentialsException.class) 
    public ResponseEntity<HttpResponse> badCredentialsException() { 
        return createHttpResponse(BAD_REQUEST, INCORRECT_CREDENTIALS); 
    } 
 
    @ExceptionHandler(AccessDeniedException.class) 
    public ResponseEntity<HttpResponse> accessDeniedException() { 
        return createHttpResponse(FORBIDDEN, NOT_ENOUGH_PERMISSION); 
    } 
 
    @ExceptionHandler(AuthenticationException.class) 
    public ResponseEntity<HttpResponse> authenticationException() { 
        return createHttpResponse(FORBIDDEN, NOT_AUTHENTICATED); 
    } 
 
    @ExceptionHandler(LockedException.class) 
    public ResponseEntity<HttpResponse> lockedException() { 
        return createHttpResponse(UNAUTHORIZED, ACCOUNT_LOCKED); 
    } 
 
    @ExceptionHandler(TokenExpiredException.class) 
    public ResponseEntity<HttpResponse> tokenExpiredException() { 
        return createHttpResponse(UNAUTHORIZED, TOKEN_EXPIRED_ERROR); 
    } 
 
    // ── Business logic exceptions ────────────────────────────────────── 
    @ExceptionHandler(EmailExistException.class) 
    public ResponseEntity<HttpResponse> emailExistException(EmailExistException ex) { 
        return createHttpResponse(BAD_REQUEST, ex.getMessage()); 
    } 
 
    @ExceptionHandler(UsernameExistException.class) 
    public ResponseEntity<HttpResponse> usernameExistException(UsernameExistException ex) { 
        return createHttpResponse(BAD_REQUEST, ex.getMessage()); 
    } 
 
    @ExceptionHandler(EmailNotFoundException.class) 
    public ResponseEntity<HttpResponse> emailNotFoundException(EmailNotFoundException ex) { 
        return createHttpResponse(BAD_REQUEST, ex.getMessage()); 
    } 
 
    @ExceptionHandler(EmailNotVerifiedException.class) 
    public ResponseEntity<HttpResponse> emailNotVerifiedException(EmailNotVerifiedException ex) { 
        return createHttpResponse(BAD_REQUEST, ex.getMessage()); 
    } 
 
    @ExceptionHandler(UserNotFoundException.class) 
    public ResponseEntity<HttpResponse> userNotFoundException(UserNotFoundException ex) { 
        return createHttpResponse(BAD_REQUEST, ex.getMessage()); 
    } 
 
    // ── HTTP / server exceptions ───────────────────────────────────────  
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<HttpResponse> methodNotSupportedException(
            HttpRequestMethodNotSupportedException ex) {
        HttpMethod supported =
            Objects.requireNonNull(ex.getSupportedHttpMethods()).iterator().next();
        return createHttpResponse(HttpStatus.METHOD_NOT_ALLOWED,
                String.format("This request method is not allowed: '%s'", supported));
    }
 
    @ExceptionHandler(Exception.class) 
    public ResponseEntity<HttpResponse> internalServerErrorException(Exception ex) 
{ 
        logger.error(ex.getMessage(), ex); 
        return createHttpResponse(INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERR); 
    } 
 
    @ExceptionHandler(jakarta.persistence.NoResultException.class) 
    public ResponseEntity<HttpResponse> notFoundException( 
            jakarta.persistence.NoResultException ex) { 
        return createHttpResponse(NOT_FOUND, ex.getMessage()); 
    } 
 
    @GetMapping(ERROR_PATH) 
    public ResponseEntity<HttpResponse> notFound404() { 
        return createHttpResponse(NOT_FOUND, NO_MAPPING_URL); 
    } 
} 