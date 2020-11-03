/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.impl;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.*;
import io.vertx.ext.web.codec.impl.BodyCodecImpl;
import io.vertx.ext.web.handler.impl.HttpStatusException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class RoutingContextImpl extends RoutingContextImplBase {

  private final RouterImpl router;
  private final HttpServerRequest request;
  private final AtomicInteger handlerSeq = new AtomicInteger();

  private Map<String, Object> data;
  private Map<String, String> pathParams;
  private MultiMap queryParams;
  private Map<Integer, Handler<Void>> headersEndHandlers;
  private Map<Integer, Handler<Void>> bodyEndHandlers;
  // clean up handlers
  private Map<Integer, Handler<AsyncResult<Void>>> endHandlers;

  private Throwable failure;
  private int statusCode = -1;
  private String normalizedPath;
  private String acceptableContentType;
  private ParsableHeaderValuesContainer parsedHeaders;

  private Buffer body;
  private Set<FileUpload> fileUploads;
  private Session session;
  private User user;

  private volatile boolean isSessionAccessed = false;
  private volatile boolean endHandlerCalled = false;

  public RoutingContextImpl(String mountPoint, RouterImpl router, HttpServerRequest request, Set<RouteImpl> routes) {
    super(mountPoint, routes);
    this.router = router;
    this.request = new HttpServerRequestWrapper(request, router.getAllowForward());

    if (request.path().length() == 0) {
      // HTTP paths must start with a '/'
      fail(400);
    } else if (request.path().charAt(0) != '/') {
      // For compatiblity we return `Not Found` when a path does not start with `/`
      fail(404);
    }
  }

  private String ensureNotNull(String string){
    return string == null ? "" : string;
  }

  private void fillParsedHeaders(HttpServerRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    String acceptCharset = request.getHeader (HttpHeaders.ACCEPT_CHARSET);
    String acceptEncoding = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
    String acceptLanguage = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
    String contentType = ensureNotNull(request.getHeader(HttpHeaders.CONTENT_TYPE));

    parsedHeaders = new ParsableHeaderValuesContainer(
        HeaderParser.sort(HeaderParser.convertToParsedHeaderValues(accept, ParsableMIMEValue::new)),
        HeaderParser.sort(HeaderParser.convertToParsedHeaderValues(acceptCharset, ParsableHeaderValue::new)),
        HeaderParser.sort(HeaderParser.convertToParsedHeaderValues(acceptEncoding, ParsableHeaderValue::new)),
        HeaderParser.sort(HeaderParser.convertToParsedHeaderValues(acceptLanguage, ParsableLanguageValue::new)),
        new ParsableMIMEValue(contentType)
    );

  }

  @Override
  public HttpServerRequest request() {
    return request;
  }

  @Override
  public HttpServerResponse response() {
    return request.response();
  }

  @Override
  public Throwable failure() {
    return failure;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public boolean failed() {
    return failure != null || statusCode != -1;
  }

  @Override
  public void next() {
    if (!iterateNext()) {
      checkHandleNoMatch();
    }
  }

  private void checkHandleNoMatch() {
    // Next called but no more matching routes
    if (failed()) {
      // Send back FAILURE
      unhandledFailure(statusCode, failure, router);
    } else {
      Handler<RoutingContext> handler = router.getErrorHandlerByStatusCode(this.matchFailure);
      this.statusCode = this.matchFailure;
      if (handler == null) { // Default 404 handling
        // Send back empty default response with status code
        this.response().setStatusCode(matchFailure);
        if (this.request().method() != HttpMethod.HEAD && matchFailure == 404) {
          // If it's a 404 let's send a body too
          this.response()
            .putHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
            .end(DEFAULT_404);
        } else {
          this.response().end();
        }
      } else
        handler.handle(this);
    }
  }

  @Override
  public void fail(int statusCode) {
    this.statusCode = statusCode;
    doFail();
  }

  @Override
  public void fail(Throwable t) {
    if (t instanceof HttpStatusException) {
      this.fail(((HttpStatusException) t).getStatusCode(), t);
    } else {
      this.fail(500, t);
    }
  }

  @Override
  public void fail(int statusCode, Throwable throwable) {
    this.statusCode = statusCode;
    this.failure = throwable == null ? new NullPointerException() : throwable;
    doFail();
  }

  @Override
  public RoutingContext put(String key, Object obj) {
    getData().put(key, obj);
    return this;
  }

  @Override
  public Vertx vertx() {
    return router.vertx();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(String key) {
    Object obj = getData().get(key);
    return (T)obj;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T remove(String key) {
    Object obj = getData().remove(key);
    return (T)obj;
  }

  @Override
  public Map<String, Object> data() {
    return getData();
  }

  @Override
  public String normalizedPath() {
    if (normalizedPath == null) {
      String path = request.path();
      if (path == null) {
        normalizedPath = "/";
      } else {
        normalizedPath = HttpUtils.normalizePath(path);
      }
    }
    return normalizedPath;
  }

  @Override
  public Cookie getCookie(String name) {
    return request.getCookie(name);
  }

  @Override
  public RoutingContext addCookie(io.vertx.core.http.Cookie cookie) {
    request.response().addCookie(cookie);
    return this;
  }

  @Override
  public Cookie removeCookie(String name, boolean invalidate) {
    return request.response().removeCookie(name, invalidate);
  }

  @Override
  public int cookieCount() {
    return request.cookieCount();
  }

  @Override
  public Map<String, Cookie> cookieMap() {
    return request.cookieMap();
  }

  @Override
  public String getBodyAsString() {
    if (body != null) {
      ParsableHeaderValuesContainer parsedHeaders = parsedHeaders();
      if (parsedHeaders != null) {
        ParsableMIMEValue contentType = parsedHeaders.contentType();
        if (contentType != null) {
          String charset = contentType.parameter("charset");
          if (charset != null) {
            return body.toString(charset);
          }
        }
      }
      return body.toString();
    } else {
      if (!seenHandler(BODY_HANDLER)) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("BodyHandler in not enabled on this route: RoutingContext.getBodyAsString(...) in always be NULL");
        }
      }
    }
    return null;
  }

  @Override
  public String getBodyAsString(String encoding) {
    return body != null ? body.toString(encoding) : null;
  }

  @Override
  public JsonObject getBodyAsJson(int maxAllowedLength) {
    if (body != null) {
      if (maxAllowedLength >= 0 && body.length() > maxAllowedLength) {
        throw new IllegalStateException("RoutingContext body size exceeds the allowed limit");
      }
      return BodyCodecImpl.JSON_OBJECT_DECODER.apply(body);
    } else {
      if (!seenHandler(BODY_HANDLER)) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("BodyHandler in not enabled on this route: RoutingContext.getBodyAsJson() in always be NULL");
        }
      }
    }
    return null;
  }

  @Override
  public JsonArray getBodyAsJsonArray(int maxAllowedLength) {
    if (body != null) {
      if (maxAllowedLength >= 0 && body.length() > maxAllowedLength) {
        throw new IllegalStateException("RoutingContext body size exceeds the allowed limit");
      }
      return BodyCodecImpl.JSON_ARRAY_DECODER.apply(body);
    } else {
      if (!seenHandler(BODY_HANDLER)) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("BodyHandler in not enabled on this route: RoutingContext.getBodyAsJsonArray(...) in always be NULL");
        }
      }
    }
    return null;
  }

  @Override
  public Buffer getBody() {
    return body;
  }

  @Override
  public void setBody(Buffer body) {
    this.body = body;
  }

  @Override
  public Set<FileUpload> fileUploads() {
    return getFileUploads();
  }

  @Override
  public void setSession(Session session) {
    this.session = session;
  }

  @Override
  public Session session() {
    this.isSessionAccessed = true;
    return session;
  }

  @Override
  public boolean isSessionAccessed(){
    return isSessionAccessed;
  }

  @Override
  public User user() {
    return user;
  }

  @Override
  public void setUser(User user) {
    this.user = user;
  }

  @Override
  public void clearUser() {
    this.user = null;
  }

  @Override
  public String getAcceptableContentType() {
    return acceptableContentType;
  }

  @Override
  public void setAcceptableContentType(String contentType) {
    this.acceptableContentType = contentType;
  }

  @Override
  public ParsableHeaderValuesContainer parsedHeaders() {
    if (parsedHeaders == null) {
      fillParsedHeaders(request);
    }
    return parsedHeaders;
  }

  @Override
  public int addHeadersEndHandler(Handler<Void> handler) {
    int seq = nextHandlerSeq();
    getHeadersEndHandlers().put(seq, handler);
    return seq;
  }

  @Override
  public boolean removeHeadersEndHandler(int handlerID) {
    return getHeadersEndHandlers().remove(handlerID) != null;
  }

  @Override
  public int addBodyEndHandler(Handler<Void> handler) {
    int seq = nextHandlerSeq();
    getBodyEndHandlers().put(seq, handler);
    return seq;
  }

  @Override
  public boolean removeBodyEndHandler(int handlerID) {
    return getBodyEndHandlers().remove(handlerID) != null;
  }

  @Override
  public int addEndHandler(Handler<AsyncResult<Void>> handler) {
    int seq = nextHandlerSeq();
    getEndHandlers().put(seq, handler);
    return seq;
  }

  @Override
  public boolean removeEndHandler(int handlerID) {
    return getEndHandlers().remove(handlerID) != null;
  }

  @Override
  public void reroute(HttpMethod method, String path) {
    if (path.charAt(0) != '/') {
      throw new IllegalArgumentException("path must start with '/'");
    }
    // change the method and path of the request
    ((HttpServerRequestWrapper) request).changeTo(method, path);
    // we need to reset the normalized path
    normalizedPath = null;
    // we also need to reset any previous status
    statusCode = -1;
    // we need to reset any response headers
    response().headers().clear();
    // reset the end handlers
    if (headersEndHandlers != null) {
      headersEndHandlers.clear();
    }
    if (bodyEndHandlers != null) {
      bodyEndHandlers.clear();
    }

    failure = null;
    restart();
  }

  @Override
  public Map<String, String> pathParams() {
    return getPathParams();
  }

  @Override
  public @Nullable String pathParam(String name) {
    return getPathParams().get(name);
  }

  @Override
  public MultiMap queryParams() {
    return getQueryParams(null);
  }

  @Override
  public MultiMap queryParams(Charset charset) {
    return getQueryParams(charset);
  }

  @Override
  public @Nullable List<String> queryParam(String query) {
    return queryParams().getAll(query);
  }

  private MultiMap getQueryParams(Charset charset) {
    // Check if query params are already parsed
    if (charset != null || queryParams == null) {
      try {
        // Decode query parameters and put inside context.queryParams
        if (charset == null) {
          queryParams = MultiMap.caseInsensitiveMultiMap();
          Map<String, List<String>> decodedParams = new QueryStringDecoder(request.uri()).parameters();
          for (Map.Entry<String, List<String>> entry : decodedParams.entrySet()) {
            queryParams.add(entry.getKey(), entry.getValue());
          }
        } else {
          MultiMap queryParams = MultiMap.caseInsensitiveMultiMap();
          Map<String, List<String>> decodedParams = new QueryStringDecoder(request.uri(), charset).parameters();
          for (Map.Entry<String, List<String>> entry : decodedParams.entrySet()) {
            queryParams.add(entry.getKey(), entry.getValue());
          }
          return queryParams;
        }
      } catch (IllegalArgumentException e) {
        throw new HttpStatusException(400, "Error while decoding query params", e);
      }
    }
    return queryParams;
  }

  private Map<String, String> getPathParams() {
    if (pathParams == null) {
      pathParams = new HashMap<>();
    }
    return pathParams;
  }

  private Map<Integer, Handler<Void>> getHeadersEndHandlers() {
    if (headersEndHandlers == null) {
      // order is important we we should traverse backwards
      headersEndHandlers = new TreeMap<>(Collections.reverseOrder());
      response().headersEndHandler(v -> headersEndHandlers.values().forEach(handler -> handler.handle(null)));
    }
    return headersEndHandlers;
  }

  private Map<Integer, Handler<Void>> getBodyEndHandlers() {
    if (bodyEndHandlers == null) {
      // order is important we we should traverse backwards
      bodyEndHandlers = new TreeMap<>(Collections.reverseOrder());
      response().bodyEndHandler(v -> bodyEndHandlers.values().forEach(handler -> handler.handle(null)));
    }
    return bodyEndHandlers;
  }

  private Map<Integer, Handler<AsyncResult<Void>>> getEndHandlers() {
    if (endHandlers == null) {
      // order is important we we should traverse backwards
      endHandlers = new TreeMap<>(Collections.reverseOrder());

      final Handler<Void> endHandler = v -> {
        if (!endHandlerCalled) {
          endHandlerCalled = true;
          endHandlers.values().forEach(handler -> handler.handle(Future.succeededFuture()));
        }
      };

      final Handler<Throwable> exceptionHandler = cause -> {
        if (!endHandlerCalled) {
          endHandlerCalled = true;
          endHandlers.values().forEach(handler -> handler.handle(Future.failedFuture(cause)));
        }
      };

      final Handler<Void> closeHandler = cause -> {
        if (!endHandlerCalled) {
          endHandlerCalled = true;
          endHandlers.values().forEach(handler -> handler.handle(Future.failedFuture("Connection closed")));
        }
      };

      response()
        .endHandler(endHandler)
        .exceptionHandler(exceptionHandler)
        .closeHandler(closeHandler);
    }

    return endHandlers;
  }

  private Set<FileUpload> getFileUploads() {
    if (fileUploads == null) {
      fileUploads = new HashSet<>();
    }
    return fileUploads;
  }

  private void doFail() {
    this.iter = router.iterator();
    currentRoute = null;
    next();
  }

  private Map<String, Object> getData() {
    if (data == null) {
      data = new HashMap<>();
    }
    return data;
  }

  private int nextHandlerSeq() {
    int seq = handlerSeq.incrementAndGet();
    if (seq == Integer.MAX_VALUE) {
      throw new IllegalStateException("Too many header/body end handlers!");
    }
    return seq;
  }

  private static final String DEFAULT_404 =
    "<html><body><h1>Resource not found</h1></body></html>";

}
