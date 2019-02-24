package org.knowm.xchange.kucoin;

import static org.knowm.xchange.kucoin.KucoinExceptionClassifier.classifyingExceptions;

import java.io.IOException;

import com.kucoin.sdk.rest.response.OrderCancelResponse;
import com.kucoin.sdk.rest.response.OrderResponse;
import com.kucoin.sdk.rest.response.Pagination;
import com.kucoin.sdk.rest.response.TradeResponse;

public class KucoinTradeServiceRaw extends KucoinBaseService {

  protected KucoinTradeServiceRaw(KucoinExchange exchange) {
    super(exchange);
  }

  public Pagination<OrderResponse> getKucoinOpenOrders(String symbol, int page, int pageSize) throws IOException {
    return classifyingExceptions(() ->
      kucoinRestClient.orderAPI().listOrders(symbol, null, null,
          "active", null, null, pageSize, page));
  }

  public Pagination<TradeResponse> getKucoinFills(String symbol, int page, int pageSize) throws IOException {
    return classifyingExceptions(() ->
      kucoinRestClient.fillAPI().listFills(symbol, null, null,
          null, null, null, pageSize, page));
  }

  public OrderCancelResponse kucoinCancelAllOrders(String symbol) throws IOException {
    return classifyingExceptions(() ->
      kucoinRestClient.orderAPI().cancelAllOrders(symbol));
  }

}