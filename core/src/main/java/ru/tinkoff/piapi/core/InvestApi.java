package ru.tinkoff.piapi.core;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MarketDataServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MarketDataStreamServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OperationsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OrdersStreamServiceGrpc;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.StopOrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;
import ru.tinkoff.piapi.core.stream.MarketDataStreamService;
import ru.tinkoff.piapi.core.stream.OrdersStreamService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Интерфейс API реальной торговли.
 * <p>
 * При использовании токена с правами только на чтение
 * вызов модифицирующих методов приводит к ошибке.
 */
public class InvestApi {

  private static final String configResourceName = "config.properties";
  private static final String targetPath = "target";
  private static final String connectionTimeoutPath = "connection-timeout";
  private static final String requestTimeoutPath = "request-timeout";
  private static final String defaultAppName = "tinkoff.invest-api-java-sdk";
  private static final String target;
  private static final Duration connectionTimeout;
  private static final Duration requestTimeout;

  static {
    var props = loadProps();
    var packageName = "ru.tinkoff.piapi.core";
    target = props.getProperty(String.format("%s.%s", packageName, targetPath));
    connectionTimeout = Duration.parse(props.getProperty(String.format("%s.%s", packageName, connectionTimeoutPath)));
    requestTimeout = Duration.parse(props.getProperty(String.format("%s.%s", packageName, requestTimeoutPath)));
  }

  private final Channel channel;
  private final boolean sandboxMode;
  private final boolean readonlyMode;
  private final UsersService userService;
  private final OperationsService operationsService;
  private final InstrumentsService instrumentsService;
  private final StopOrdersService stopOrdersService;
  private final OrdersService ordersService;
  private final OrdersStreamService ordersStreamService;
  private final MarketDataService marketDataService;
  private final MarketDataStreamService marketDataStreamService;
  private final SandboxService sandboxService;

  private InvestApi(@Nonnull Channel channel, boolean sandboxMode, boolean readonlyMode) {
    assert !(sandboxMode && readonlyMode) :
      "Нельзя одновременно установить режимы \"песочница\" и \"только для чтения\" ";

    this.channel = channel;
    this.sandboxMode = sandboxMode;
    this.readonlyMode = readonlyMode;
    this.instrumentsService = new InstrumentsService(
      InstrumentsServiceGrpc.newBlockingStub(channel),
      InstrumentsServiceGrpc.newStub(channel));
    this.marketDataService = new MarketDataService(
      MarketDataServiceGrpc.newBlockingStub(channel),
      MarketDataServiceGrpc.newStub(channel));
    this.marketDataStreamService = new MarketDataStreamService(MarketDataStreamServiceGrpc.newStub(channel));
    this.ordersStreamService = new OrdersStreamService(OrdersStreamServiceGrpc.newStub(channel));
    if (sandboxMode) {
      this.sandboxService = new SandboxService(
        SandboxServiceGrpc.newBlockingStub(channel),
        SandboxServiceGrpc.newStub(channel));

      this.userService = null;
      this.operationsService = null;
      this.stopOrdersService = null;
      this.ordersService = null;
    } else {
      this.userService = new UsersService(
        UsersServiceGrpc.newBlockingStub(channel),
        UsersServiceGrpc.newStub(channel));
      this.operationsService = new OperationsService(
        OperationsServiceGrpc.newBlockingStub(channel),
        OperationsServiceGrpc.newStub(channel));
      this.stopOrdersService = new StopOrdersService(
        StopOrdersServiceGrpc.newBlockingStub(channel),
        StopOrdersServiceGrpc.newStub(channel),
        readonlyMode);
      this.ordersService = new OrdersService(
        OrdersServiceGrpc.newBlockingStub(channel),
        OrdersServiceGrpc.newStub(channel),
        readonlyMode);

      this.sandboxService = null;
    }
  }

  /**
   * Создаёт экземпляр API для реальной торговли с использованием
   * готовой конфигурации GRPC-соединения.
   * <p>
   * ВНИМАНИЕ! Конфигурация должна включать в себя авторизацию
   * с использованием необходимого токена для реальной торговли.
   *
   * @param channel Конфигурация GRPC-соединения.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi create(@Nonnull Channel channel) {
    return new InvestApi(channel, false, false);
  }

  /**
   * Создаёт экземпляр API для реальной торговли с использованием
   * готовой конфигурации GRPC-соединения.
   * <p>
   *
   * @param token Токен для торговли.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi create(@Nonnull String token) {
    return new InvestApi(defaultChannel(token, null), false, false);
  }

  /**
   * Создаёт экземпляр API для реальной торговли с использованием
   * готовой конфигурации GRPC-соединения.
   * <p>
   *
   * @param token Токен для торговли.
   * @param appName Application name для сбора статистики.
   *                Подробности в <a href="https://tinkoff.github.io/investAPI/grpc/#appname">документации</a>.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi create(@Nonnull String token, @Nonnull String appName) {
    return new InvestApi(defaultChannel(token, appName), false, false);
  }

  /**
   * Создаёт экземпляр API в режиме "только для чтения"
   * с использованием готовой конфигурации GRPC-соединения.
   * <p>
   * ВНИМАНИЕ! Конфигурация должна включать в себя авторизацию
   * с использованием необходимого токена для режима "только для чтения".
   *
   * @param channel Конфигурация GRPC-соединения.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi createReadonly(@Nonnull Channel channel) {
    return new InvestApi(channel, false, true);
  }

  /**
   * Создаёт экземпляр API в режиме "только для чтения"
   * с использованием готовой конфигурации GRPC-соединения.
   * <p>
   *
   * @param token Токен для торговли.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi createReadonly(@Nonnull String token) {
    return new InvestApi(defaultChannel(token, null), false, true);
  }

  /**
   * Создаёт экземпляр API в режиме "только для чтения"
   * с использованием готовой конфигурации GRPC-соединения.
   * <p>
   *
   * @param token Токен для торговли.
   * @param appName Application name для сбора статистики.
   *                Подробности в <a href="https://tinkoff.github.io/investAPI/grpc/#appname">документации</a>.
   * @return Экземпляр API для реальной торговли.
   */
  @Nonnull
  public static InvestApi createReadonly(@Nonnull String token, @Nonnull String appName) {
    return new InvestApi(defaultChannel(token, appName), false, true);
  }

  /**
   * Создаёт экземпляр API для работы в "песочнице" с использованием
   * готовой конфигурации GRPC-соединения.
   * <p>
   * ВНИМАНИЕ! Конфигурация должна включать в себя авторизацию
   * с использованием необходимого токена для "песочницы".
   *
   * @param channel Конфигурация GRPC-соединения.
   * @return Экземпляр API "песочницы".
   */
  @Nonnull
  public static InvestApi createSandbox(@Nonnull Channel channel) {
    return new InvestApi(channel, true, false);
  }


  /**
   * Создаёт экземпляр API для работы в "песочнице" с использованием
   * готовой конфигурации GRPC-соединения.
   * <p>
   *
   * @param token Токен для торговли.
   * @return Экземпляр API "песочницы".
   */
  @Nonnull
  public static InvestApi createSandbox(@Nonnull String token) {
    return new InvestApi(defaultChannel(token, null), true, false);
  }

  /**
   * Создаёт экземпляр API для работы в "песочнице" с использованием
   * готовой конфигурации GRPC-соединения.
   * Подробности про appName в <a href="https://tinkoff.github.io/investAPI/grpc/#appname">документации</a>.
   * <p>
   *
   * @param token Токен для торговли.
   * @param appName Application name для сбора статистики.
   *                Подробности в <a href="https://tinkoff.github.io/investAPI/grpc/#appname">документации</a>.
   * @return Экземпляр API "песочницы".
   */
  @Nonnull
  public static InvestApi createSandbox(@Nonnull String token, @Nonnull String appName) {
    return new InvestApi(defaultChannel(token, appName), true, false);
  }

  @Nonnull
  public static Channel defaultChannel(String token, String appName) {
    var headers = new Metadata();
    addAuthHeader(headers, token);
    addAppNameHeader(headers, appName);

    return OkHttpChannelBuilder
      .forTarget(target)
      .intercept(
        new LoggingInterceptor(),
        MetadataUtils.newAttachHeadersInterceptor(headers),
        new TimeoutInterceptor(requestTimeout))
      .useTransportSecurity()
      .keepAliveTimeout(60, TimeUnit.SECONDS)
      .build();
  }

  public static void addAppNameHeader(@Nonnull Metadata metadata, @Nullable String appName) {
    var key = Metadata.Key.of("x-app-name", Metadata.ASCII_STRING_MARSHALLER);
    metadata.put(key, appName == null ? defaultAppName : appName);
  }

  public static void addAuthHeader(@Nonnull Metadata metadata, @Nonnull String token) {
    var authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
    metadata.put(authKey, "Bearer " + token);
  }

  private static Properties loadProps() {
    var loader = Thread.currentThread().getContextClassLoader();
    var props = new Properties();
    try (var resourceStream = loader.getResourceAsStream(configResourceName)) {
      props.load(resourceStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return props;
  }

  /**
   * Получение сервиса котировок.
   *
   * @return Сервис котировок.
   */
  @Nonnull
  public MarketDataService getMarketDataService() {
    return marketDataService;
  }

  /**
   * Получение сервиса стримов котировок.
   *
   * @return Сервис стримов котировок.
   */
  @Nonnull
  public MarketDataStreamService getMarketDataStreamService() {
    return marketDataStreamService;
  }

  /**
   * Получение сервиса торговых поручений.
   *
   * @return Сервис торговых поручений.
   */
  @Nonnull
  public OrdersService getOrdersService() {
    if (sandboxMode) throw new IllegalStateException("Недоступно в режиме \"песочницы\".");

    return ordersService;
  }

  /**
   * Получение сервиса стримов торговых поручений.
   *
   * @return Сервис стримов торговых поручений.
   */
  @Nonnull
  public OrdersStreamService getOrdersStreamService() {
    if (sandboxMode) throw new IllegalStateException("Недоступно в режиме \"песочницы\".");

    return ordersStreamService;
  }

  /**
   * Получение сервиса стоп-заявок.
   *
   * @return Севис стоп-заявок.
   */
  @Nonnull
  public StopOrdersService getStopOrdersService() {
    if (sandboxMode) throw new IllegalStateException("Недоступно в режиме \"песочницы\".");

    return stopOrdersService;
  }

  /**
   * Получение сервиса инструментов.
   *
   * @return Сервис инструментов.
   */
  @Nonnull
  public InstrumentsService getInstrumentsService() {
    return instrumentsService;
  }

  /**
   * Получение сервиса операций.
   *
   * @return Сервис операций.
   */
  @Nonnull
  public OperationsService getOperationsService() {
    if (sandboxMode) throw new IllegalStateException("Недоступно в режиме \"песочницы\".");

    return operationsService;
  }

  /**
   * Получение сервиса аккаунтов.
   *
   * @return Сервис аккаунтов.
   */
  @Nonnull
  public UsersService getUserService() {
    if (sandboxMode) throw new IllegalStateException("Недоступно в режиме \"песочницы\".");

    return userService;
  }

  /**
   * Получение сервиса "песочница".
   *
   * @return Сервис "песочница".
   */
  @Nonnull
  public SandboxService getSandboxService() {
    if (!sandboxMode) throw new IllegalStateException("Необходим режим \"песочницы\".");

    return sandboxService;
  }

  /**
   * Получение GRPC-подключения.
   *
   * @return GRPC-подключения.
   */
  @Nonnull
  public Channel getChannel() {
    return channel;
  }

  /**
   * Получение флага режима "песочницы".
   * <p>
   * Всега возвращает {@code false}, если действует режим "только для чтения".
   *
   * @return Флаг режима "песочницы".
   */
  public boolean isSandboxMode() {
    return sandboxMode;
  }

  /**
   * Получение флага режима "только для чтения".
   * <p>
   * Всега возвращает {@code false}, если действует режим "песочницы".
   *
   * @return Флаг режима "только для чтения".
   */
  public boolean isReadonlyMode() {
    return readonlyMode;
  }

  static class TimeoutInterceptor implements ClientInterceptor {
    private final Duration timeout;

    public TimeoutInterceptor(Duration timeout) {
      this.timeout = timeout;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      if (method.getType() == MethodDescriptor.MethodType.UNARY) {
        callOptions = callOptions.withDeadlineAfter(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
      }

      return next.newCall(method, callOptions);
    }
  }

  static class LoggingInterceptor implements ClientInterceptor {

    private final Logger logger = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new LoggingClientCall<>(
        next.newCall(method, callOptions), logger, method);
    }
  }

  static class LoggingClientCall<ReqT, RespT>
    extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

    private final Logger logger;
    private final MethodDescriptor<ReqT, RespT> method;

    LoggingClientCall(
      ClientCall<ReqT, RespT> call,
      Logger logger,
      MethodDescriptor<ReqT, RespT> method) {
      super(call);
      this.logger = logger;
      this.method = method;
    }

    @Override
    public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
      logger.debug(
        "Готовится вызов метода {} сервиса {}.",
        method.getBareMethodName(),
        method.getServiceName());
      super.start(
        new LoggingClientCallListener<>(responseListener, logger, method),
        headers);
    }
  }

  static class LoggingClientCallListener<RespT>
    extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {

    private static final Metadata.Key<String> trackingIdKey =
      Metadata.Key.of("x-tracking-id", Metadata.ASCII_STRING_MARSHALLER);

    private final Logger logger;
    private final MethodDescriptor<?, RespT> method;
    volatile private String lastTrackingId;

    LoggingClientCallListener(
      ClientCall.Listener<RespT> listener,
      Logger logger,
      MethodDescriptor<?, RespT> method) {
      super(listener);
      this.logger = logger;
      this.method = method;
    }

    @Override
    public void onHeaders(Metadata headers) {
      lastTrackingId = headers.get(trackingIdKey);
      delegate().onHeaders(headers);
    }

    @Override
    public void onMessage(RespT message) {
      if (method.getType() == MethodDescriptor.MethodType.UNARY) {
        logger.debug(
          "Пришёл ответ от метода {} сервиса {}. (x-tracking-id = {})",
          method.getBareMethodName(),
          method.getServiceName(),
          lastTrackingId);
      } else {
        logger.debug(
          "Пришло сообщение от потока {} сервиса {}.",
          method.getBareMethodName(),
          method.getServiceName());
      }

      delegate().onMessage(message);
    }
  }
}
