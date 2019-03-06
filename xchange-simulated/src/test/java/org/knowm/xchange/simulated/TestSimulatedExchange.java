package org.knowm.xchange.simulated;

import static java.math.BigDecimal.ZERO;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.knowm.xchange.currency.Currency.BTC;
import static org.knowm.xchange.currency.Currency.USD;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USD;
import static org.knowm.xchange.dto.Order.OrderStatus.NEW;
import static org.knowm.xchange.dto.Order.OrderStatus.PARTIALLY_FILLED;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;
import static org.knowm.xchange.simulated.SimulatedExchange.ACCOUNT_FACTORY_PARAM;
import static org.knowm.xchange.simulated.SimulatedExchange.ENGINE_FACTORY_PARAM;

import java.io.IOException;
import java.math.BigDecimal;

import org.junit.Before;
import org.junit.Test;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.FundsExceededException;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParamCurrencyPair;

public class TestSimulatedExchange {

  private static final BigDecimal INITIAL_BALANCE = new BigDecimal(1000);

  private SimulatedExchange exchange;
  private MatchingEngineFactory matchingEngineFactory;
  private AccountFactory accountFactory;

  @Before
  public void setup() throws IOException {

    // By default, the matching engines are scoped to each instance of the Exchange. This ensures
    // that all instances share the same engine within the scope of each test.
    accountFactory = new AccountFactory();
    matchingEngineFactory = new MatchingEngineFactory(accountFactory);

    // As a market maker, fill the order book with buy/sell orders
    mockMarket();

    // This is what we'll use for trade testing
    ExchangeSpecification exchangeSpecification =
        new ExchangeSpecification(SimulatedExchange.class);
    exchangeSpecification.setApiKey("Tester");
    exchangeSpecification.setExchangeSpecificParametersItem(
        ENGINE_FACTORY_PARAM, matchingEngineFactory);
    exchangeSpecification.setExchangeSpecificParametersItem(ACCOUNT_FACTORY_PARAM, accountFactory);
    exchange = (SimulatedExchange) ExchangeFactory.INSTANCE.createExchange(exchangeSpecification);

    // Provide an initial balance
    exchange.getAccountService().deposit(USD, INITIAL_BALANCE);
    exchange.getAccountService().deposit(BTC, INITIAL_BALANCE);
  }

  @Test
  public void testStartPosition() throws IOException {
    // When
    OrderBook orderBook = exchange.getMarketDataService().getOrderBook(BTC_USD);
    Ticker ticker = exchange.getMarketDataService().getTicker(BTC_USD);
    Balance baseBalance = exchange.getAccountService().getAccountInfo().getWallet().getBalance(BTC);
    Balance counterBalance =
        exchange.getAccountService().getAccountInfo().getWallet().getBalance(USD);

    // Then
    assertThat(orderBook.getAsks(), hasSize(4));
    assertThat(orderBook.getAsks().get(0).getLimitPrice(), equalTo(new BigDecimal(98)));
    assertThat(orderBook.getBids(), hasSize(6));
    assertThat(orderBook.getBids().get(0).getLimitPrice(), equalTo(new BigDecimal(97)));
    assertThat(ticker.getAsk(), equalTo(new BigDecimal(98)));
    assertThat(ticker.getBid(), equalTo(new BigDecimal(97)));
    assertThat(ticker.getLast(), nullValue());
    assertThat(getOpenOrders().getAllOpenOrders(), empty());
    assertThat(getTradeHistory(exchange).getTrades(), empty());
    assertThat(baseBalance.getAvailable(), equalTo(INITIAL_BALANCE));
    assertThat(baseBalance.getTotal(), equalTo(INITIAL_BALANCE));
    assertThat(baseBalance.getFrozen(), equalTo(ZERO));
    assertThat(counterBalance.getAvailable(), equalTo(INITIAL_BALANCE));
    assertThat(counterBalance.getTotal(), equalTo(INITIAL_BALANCE));
    assertThat(counterBalance.getFrozen(), equalTo(ZERO));
  }

  @Test(expected = ExchangeException.class)
  public void testInsufficientLiquidityBid() throws IOException {
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(BID, BTC_USD).originalAmount(new BigDecimal("250")).build());
  }

  @Test(expected = ExchangeException.class)
  public void testInsufficientLiquidityAsk() throws IOException {
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(ASK, BTC_USD).originalAmount(new BigDecimal("1002.1")).build());
  }

  @Test(expected = FundsExceededException.class)
  public void testInsufficientFundsBid() throws IOException {
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(BID, BTC_USD).originalAmount(new BigDecimal("150")).build());
  }

  @Test(expected = FundsExceededException.class)
  public void testInsufficientFundsAsk() throws IOException {
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(ASK, BTC_USD)
                .originalAmount(new BigDecimal("1000.01"))
                .build());
  }

  @Test
  public void testTradeHistoryIsolation() throws IOException {

    // Given
    ExchangeSpecification exchangeSpecification =
        new ExchangeSpecification(SimulatedExchange.class);
    exchangeSpecification.setApiKey("SomeoneElse");
    exchangeSpecification.setExchangeSpecificParametersItem(
        ENGINE_FACTORY_PARAM, matchingEngineFactory);
    Exchange someoneElsesExchange = ExchangeFactory.INSTANCE.createExchange(exchangeSpecification);

    // When
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(ASK, BTC_USD).originalAmount(new BigDecimal("0.7")).build());

    // Then
    assertThat(exchange.getMarketDataService().getTrades(BTC_USD).getTrades(), hasSize(3));
    assertThat(
        someoneElsesExchange.getMarketDataService().getTrades(BTC_USD).getTrades(), hasSize(3));
    assertThat(getTradeHistory(exchange).getTrades(), hasSize(3));
    assertThat(getTradeHistory(someoneElsesExchange).getTrades(), empty());
  }

  @Test
  public void testTradingMarketAsk() throws IOException {

    // When
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(ASK, BTC_USD).originalAmount(new BigDecimal("0.7")).build());
    OrderBook orderBook = exchange.getMarketDataService().getOrderBook(BTC_USD);
    Ticker ticker = exchange.getMarketDataService().getTicker(BTC_USD);
    Balance baseBalance = exchange.getAccountService().getAccountInfo().getWallet().getBalance(BTC);
    Balance counterBalance =
        exchange.getAccountService().getAccountInfo().getWallet().getBalance(USD);

    // Then
    assertThat(orderBook.getAsks(), hasSize(4));
    assertThat(orderBook.getBids(), hasSize(5));
    assertThat(ticker.getAsk(), equalTo(new BigDecimal(98)));
    assertThat(ticker.getBid(), equalTo(new BigDecimal(96)));
    assertThat(ticker.getLast(), equalTo(new BigDecimal(96)));
    assertThat(getTradeHistory(exchange).getTrades(), hasSize(3));

    BigDecimal expectedUsdProceeds =
        new BigDecimal(97)
            .multiply(new BigDecimal("0.40"))
            .add(new BigDecimal(96).multiply(new BigDecimal("0.30")));
    assertThat(
        baseBalance.getAvailable(), equalTo(INITIAL_BALANCE.subtract(new BigDecimal("0.70"))));
    assertThat(baseBalance.getTotal(), equalTo(INITIAL_BALANCE.subtract(new BigDecimal("0.70"))));
    assertThat(baseBalance.getFrozen(), equalTo(ZERO));
    assertThat(counterBalance.getAvailable(), equalTo(INITIAL_BALANCE.add(expectedUsdProceeds)));
    assertThat(counterBalance.getTotal(), equalTo(INITIAL_BALANCE.add(expectedUsdProceeds)));
    assertThat(counterBalance.getFrozen(), equalTo(ZERO));
  }

  @Test
  public void testTradingLimitAsk() throws IOException {

    // When
    String orderId =
        exchange
            .getTradeService()
            .placeLimitOrder(
                new LimitOrder.Builder(ASK, BTC_USD)
                    .limitPrice(new BigDecimal(97))
                    .originalAmount(new BigDecimal("0.7"))
                    .build());
    OrderBook orderBook = exchange.getMarketDataService().getOrderBook(BTC_USD);
    Ticker ticker = exchange.getMarketDataService().getTicker(BTC_USD);
    Balance baseBalance = exchange.getAccountService().getAccountInfo().getWallet().getBalance(BTC);
    Balance counterBalance =
        exchange.getAccountService().getAccountInfo().getWallet().getBalance(USD);

    // THen
    assertThat(orderBook.getAsks(), hasSize(5));
    assertThat(orderBook.getBids(), hasSize(5));
    assertThat(ticker.getAsk(), equalTo(new BigDecimal(97)));
    assertThat(ticker.getBid(), equalTo(new BigDecimal(96)));
    assertThat(ticker.getLast(), equalTo(new BigDecimal(97)));

    OpenOrders orders = getOpenOrders();
    assertThat(orders.getOpenOrders(), hasSize(1));
    assertThat(orders.getOpenOrders().get(0).getRemainingAmount(), equalTo(new BigDecimal("0.3")));
    assertThat(orders.getOpenOrders().get(0).getCumulativeAmount(), equalTo(new BigDecimal("0.4")));
    assertThat(orders.getOpenOrders().get(0).getAveragePrice(), equalTo(new BigDecimal(97)));
    assertThat(orders.getOpenOrders().get(0).getId(), equalTo(orderId));
    assertThat(orders.getOpenOrders().get(0).getStatus(), equalTo(PARTIALLY_FILLED));

    assertThat(getTradeHistory(exchange).getTrades(), hasSize(1));

    BigDecimal expectedUsdProceeds = new BigDecimal(97).multiply(new BigDecimal("0.4"));
    assertThat(baseBalance.getTotal(), equalTo(INITIAL_BALANCE.subtract(new BigDecimal("0.4"))));
    assertThat(baseBalance.getFrozen(), equalTo(new BigDecimal("0.3")));
    assertThat(
        baseBalance.getAvailable(), equalTo(INITIAL_BALANCE.subtract(new BigDecimal("0.7"))));
    assertThat(counterBalance.getTotal(), equalTo(INITIAL_BALANCE.add(expectedUsdProceeds)));
    assertThat(counterBalance.getFrozen(), equalTo(ZERO));
    assertThat(counterBalance.getAvailable(), equalTo(INITIAL_BALANCE.add(expectedUsdProceeds)));
  }

  @Test
  public void testTradingMarketBid() throws IOException {

    // When
    exchange
        .getTradeService()
        .placeMarketOrder(
            new MarketOrder.Builder(BID, BTC_USD).originalAmount(new BigDecimal("0.56")).build());
    OrderBook orderBook = exchange.getMarketDataService().getOrderBook(BTC_USD);
    Ticker ticker = exchange.getMarketDataService().getTicker(BTC_USD);
    Balance baseBalance = exchange.getAccountService().getAccountInfo().getWallet().getBalance(BTC);
    Balance counterBalance =
        exchange.getAccountService().getAccountInfo().getWallet().getBalance(USD);

    // THen
    assertThat(orderBook.getAsks(), hasSize(3));
    assertThat(orderBook.getBids(), hasSize(6));
    assertThat(ticker.getAsk(), equalTo(new BigDecimal(99)));
    assertThat(ticker.getBid(), equalTo(new BigDecimal(97)));
    assertThat(ticker.getLast(), equalTo(new BigDecimal(99)));
    assertThat(getTradeHistory(exchange).getTrades(), hasSize(3));

    BigDecimal expectedUsdCost =
        new BigDecimal(98)
            .multiply(new BigDecimal("0.3"))
            .add(new BigDecimal(99).multiply(new BigDecimal("0.26")));
    assertThat(baseBalance.getAvailable(), equalTo(INITIAL_BALANCE.add(new BigDecimal("0.56"))));
    assertThat(baseBalance.getTotal(), equalTo(INITIAL_BALANCE.add(new BigDecimal("0.56"))));
    assertThat(baseBalance.getFrozen(), equalTo(ZERO));
    assertThat(counterBalance.getAvailable(), equalTo(INITIAL_BALANCE.subtract(expectedUsdCost)));
    assertThat(counterBalance.getTotal(), equalTo(INITIAL_BALANCE.subtract(expectedUsdCost)));
    assertThat(counterBalance.getFrozen(), equalTo(ZERO));
  }

  @Test
  public void testTradingLimitBid() throws IOException {

    // When
    String orderId1 =
        exchange
            .getTradeService()
            .placeLimitOrder(
                new LimitOrder.Builder(BID, BTC_USD)
                    .limitPrice(new BigDecimal(99))
                    .originalAmount(new BigDecimal("0.7"))
                    .build());
    String orderId2 =
        exchange
            .getTradeService()
            .placeLimitOrder(
                new LimitOrder.Builder(BID, BTC_USD)
                    .limitPrice(new BigDecimal(90))
                    .originalAmount(new BigDecimal("1"))
                    .build());
    OrderBook orderBook = exchange.getMarketDataService().getOrderBook(BTC_USD);
    Ticker ticker = exchange.getMarketDataService().getTicker(BTC_USD);
    Balance baseBalance = exchange.getAccountService().getAccountInfo().getWallet().getBalance(BTC);
    Balance counterBalance =
        exchange.getAccountService().getAccountInfo().getWallet().getBalance(USD);

    // THen
    assertThat(orderBook.getAsks(), hasSize(2));
    assertThat(orderBook.getBids(), hasSize(8));
    assertThat(ticker.getAsk(), equalTo(new BigDecimal(100)));
    assertThat(ticker.getBid(), equalTo(new BigDecimal(99)));
    assertThat(ticker.getLast(), equalTo(new BigDecimal(99)));

    OpenOrders orders = getOpenOrders();
    assertThat(orders.getOpenOrders(), hasSize(2));
    Order order1 =
        orders.getAllOpenOrders().stream()
            .filter(o -> o.getId().equals(orderId1))
            .findFirst()
            .get();
    Order order2 =
        orders.getAllOpenOrders().stream()
            .filter(o -> o.getId().equals(orderId2))
            .findFirst()
            .get();
    assertThat(order1.getRemainingAmount(), equalTo(new BigDecimal("0.10")));
    assertThat(order1.getCumulativeAmount(), equalTo(new BigDecimal("0.60")));
    assertThat(order1.getAveragePrice(), equalTo(new BigDecimal("98.50")));
    assertThat(order1.getStatus(), equalTo(PARTIALLY_FILLED));
    assertThat(order2.getRemainingAmount(), equalTo(new BigDecimal(1)));
    assertThat(order2.getCumulativeAmount(), equalTo(ZERO));
    assertThat(order2.getAveragePrice(), nullValue());
    assertThat(order2.getStatus(), equalTo(NEW));

    assertThat(getTradeHistory(exchange).getTrades(), hasSize(3));

    BigDecimal expectedUsdCost =
        new BigDecimal(98)
            .multiply(new BigDecimal("0.30"))
            .add(new BigDecimal(99).multiply(new BigDecimal("0.30")));
    BigDecimal expectedUsdReserved =
        new BigDecimal(99)
            .multiply(new BigDecimal("0.10"))
            .add(new BigDecimal(90).multiply(new BigDecimal(1)));
    assertThat(baseBalance.getTotal(), equalTo(INITIAL_BALANCE.add(new BigDecimal("0.60"))));
    assertThat(baseBalance.getFrozen(), equalTo(ZERO));
    assertThat(baseBalance.getAvailable(), equalTo(INITIAL_BALANCE.add(new BigDecimal("0.60"))));
    assertThat(counterBalance.getTotal(), equalTo(INITIAL_BALANCE.subtract(expectedUsdCost)));
    assertThat(counterBalance.getFrozen(), equalTo(expectedUsdReserved));
    assertThat(
        counterBalance.getAvailable(),
        equalTo(INITIAL_BALANCE.subtract(expectedUsdCost).subtract(expectedUsdReserved)));
  }

  private void placeMMOrder(
      SimulatedExchange exchange, OrderType orderType, BigDecimal price, BigDecimal amount)
      throws IOException {
    exchange
        .getTradeService()
        .placeLimitOrderUnrestricted(
            new LimitOrder.Builder(orderType, BTC_USD)
                .limitPrice(price)
                .originalAmount(amount)
                .build());
  }

  private OpenOrders getOpenOrders() throws IOException {
    OpenOrdersParamCurrencyPair params = exchange.getTradeService().createOpenOrdersParams();
    params.setCurrencyPair(BTC_USD);
    return exchange.getTradeService().getOpenOrders(params);
  }

  private UserTrades getTradeHistory(Exchange exchangeToUse) throws IOException {
    TradeHistoryParamCurrencyPair params =
        (TradeHistoryParamCurrencyPair) exchangeToUse.getTradeService().createTradeHistoryParams();
    params.setCurrencyPair(BTC_USD);
    return exchangeToUse.getTradeService().getTradeHistory(params);
  }

  private void mockMarket() throws IOException {
    ExchangeSpecification exchangeSpecification =
        new ExchangeSpecification(SimulatedExchange.class);
    exchangeSpecification.setApiKey("MarketMakers");
    exchangeSpecification.setExchangeSpecificParametersItem(
        ENGINE_FACTORY_PARAM, matchingEngineFactory);
    exchangeSpecification.setExchangeSpecificParametersItem(ACCOUNT_FACTORY_PARAM, accountFactory);
    SimulatedExchange marketMakerExchange =
        (SimulatedExchange) ExchangeFactory.INSTANCE.createExchange(exchangeSpecification);
    marketMakerExchange.getAccountService().deposit(USD, new BigDecimal(10000));
    marketMakerExchange.getAccountService().deposit(BTC, new BigDecimal(10000));
    placeMMOrder(marketMakerExchange, ASK, new BigDecimal(10000), new BigDecimal("200"));
    placeMMOrder(marketMakerExchange, ASK, new BigDecimal(100), new BigDecimal("0.1"));
    placeMMOrder(marketMakerExchange, ASK, new BigDecimal(99), new BigDecimal("0.05"));
    placeMMOrder(marketMakerExchange, ASK, new BigDecimal(99), new BigDecimal("0.25"));
    placeMMOrder(marketMakerExchange, ASK, new BigDecimal(98), new BigDecimal("0.3"));
    // ----
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(97), new BigDecimal("0.4"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(96), new BigDecimal("0.25"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(96), new BigDecimal("0.25"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(95), new BigDecimal("0.6"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(94), new BigDecimal("0.7"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(93), new BigDecimal("0.8"));
    placeMMOrder(marketMakerExchange, BID, new BigDecimal(1), new BigDecimal("1002"));
  }
}
