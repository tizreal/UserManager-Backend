package com.tca.UserManager.domain;

import java.util.Date;
import java.text.SimpleDateFormat;
import org.springframework.http.HttpStatus;

public class HttpResponse {

	Date timeStamp; // When the response was generated
	int httpStatusCode; // Numeric code e.g. 200, 400, 500
	HttpStatus httpStatus; // Spring enum e.g. HttpStatus.BAD_REQUEST
	String reason; // Short phrase e.g. "Bad Request"
	String message; // Detailed explanation e.g. "Email already exists"

	public HttpResponse(int httpStatusCode, HttpStatus httpStatus, String reason, String message) {
		this.timeStamp = new Date();
		this.httpStatusCode = httpStatusCode;
		this.httpStatus = httpStatus;
		this.reason = reason;
		this.message = message;
	}

	public Date getTimeStamp() {
		return timeStamp;
	}

	public int getHttpStatusCode() {
		return httpStatusCode;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getReason() {
		return reason;
	}

	public String getMessage() {
		return message;
	}

	@Override
	public String toString() {
		String ts = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(this.timeStamp);

		return "HttpResponse [" + ts + ", " + httpStatusCode + ", " + message + "]";
	}
}