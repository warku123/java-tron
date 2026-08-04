package org.tron.core.services.filter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.MetricLabels;
import org.tron.common.prometheus.Metrics;

@Slf4j(topic = "httpInterceptor")
public class HttpInterceptor implements Filter {

  private final int HTTP_BAD_REQUEST = 400;
  private final int HTTP_NOT_ACCEPTABLE = 406;

  @Override
  public void init(FilterConfig filterConfig) {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    String endpoint = MetricLabels.UNDEFINED;
    try {
      if (!(request instanceof HttpServletRequest)) {
        chain.doFilter(request, response);
        return;
      }
      String contextPath = ((HttpServletRequest) request).getContextPath();
      endpoint = contextPath + ((HttpServletRequest) request).getServletPath();
      CharResponseWrapper responseWrapper = new CharResponseWrapper(
              (HttpServletResponse) response);
      chain.doFilter(request, responseWrapper);
      HttpServletResponse resp = (HttpServletResponse) response;
      int size = responseWrapper.getByteSize();
      if (resp.getStatus() >= HTTP_BAD_REQUEST && resp.getStatus() <= HTTP_NOT_ACCEPTABLE) {
        Metrics.histogramObserve(MetricKeys.Histogram.HTTP_BYTES,
                size, MetricLabels.UNDEFINED, String.valueOf(responseWrapper.getStatus()));
        return;
      }
      Metrics.histogramObserve(MetricKeys.Histogram.HTTP_BYTES,
              size, endpoint, String.valueOf(responseWrapper.getStatus()));
    } catch (Exception e) {
      if (e instanceof BadMessageException
          && ((BadMessageException) e).getCode() == HttpStatus.PAYLOAD_TOO_LARGE_413) {
        throw (BadMessageException) e;
      }
    }
  }

  @Override
  public void destroy() {
  }
}
