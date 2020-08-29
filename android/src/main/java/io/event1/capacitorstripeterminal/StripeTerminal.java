package io.event1.capacitorstripeterminal;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.stripe.stripeterminal.Terminal;
import com.stripe.stripeterminal.callable.Callback;
import com.stripe.stripeterminal.callable.Cancelable;
import com.stripe.stripeterminal.callable.ConnectionTokenCallback;
import com.stripe.stripeterminal.callable.ConnectionTokenProvider;
import com.stripe.stripeterminal.callable.DiscoveryListener;
import com.stripe.stripeterminal.callable.PaymentIntentCallback;
import com.stripe.stripeterminal.callable.ReaderCallback;
import com.stripe.stripeterminal.callable.ReaderDisplayListener;
import com.stripe.stripeterminal.callable.ReaderSoftwareUpdateCallback;
import com.stripe.stripeterminal.callable.ReaderSoftwareUpdateListener;
import com.stripe.stripeterminal.callable.TerminalListener;
import com.stripe.stripeterminal.log.LogLevel;
import com.stripe.stripeterminal.model.external.ConnectionStatus;
import com.stripe.stripeterminal.model.external.ConnectionTokenException;
import com.stripe.stripeterminal.model.external.DeviceType;
import com.stripe.stripeterminal.model.external.DiscoveryConfiguration;
import com.stripe.stripeterminal.model.external.PaymentIntent;
import com.stripe.stripeterminal.model.external.PaymentIntentParameters;
import com.stripe.stripeterminal.model.external.PaymentStatus;
import com.stripe.stripeterminal.model.external.Reader;
import com.stripe.stripeterminal.model.external.ReaderDisplayMessage;
import com.stripe.stripeterminal.model.external.ReaderEvent;
import com.stripe.stripeterminal.model.external.ReaderInputOptions;
import com.stripe.stripeterminal.model.external.ReaderSoftwareUpdate;
import com.stripe.stripeterminal.model.external.TerminalException;
import java.util.List;
import javax.annotation.Nonnull;
import org.jetbrains.annotations.NotNull;

@NativePlugin
public class StripeTerminal
  extends Plugin
  implements ConnectionTokenProvider, TerminalListener, DiscoveryListener {
  Cancelable pendingDiscoverReaders = null;
  ConnectionTokenCallback pendingConnectionTokenCallback = null;
  ReaderEvent lastReaderEvent = ReaderEvent.CARD_REMOVED;
  List<? extends Reader> discoveredReadersList = null;

  @PluginMethod
  public void initialize(PluginCall call) {
    try {
      // Check if stripe is initialized
      Terminal.getInstance();

      JSObject ret = new JSObject();
      ret.put("isInitialized", true);

      call.resolve(ret);
      return;
    } catch (IllegalStateException e) {}

    pendingConnectionTokenCallback = null;
    abortDiscoverReaders();
    // abortCreatePayment();
    // abortInstallUpdate();

    LogLevel logLevel = LogLevel.VERBOSE;
    ConnectionTokenProvider tokenProvider = this;
    TerminalListener terminalListener = this;

    String err = "";
    boolean isInitialized = false;
    try {
      Terminal.initTerminal(
        getBridge().getActivity(),
        logLevel,
        tokenProvider,
        terminalListener
      );
      lastReaderEvent = ReaderEvent.CARD_REMOVED;
      isInitialized = true;
    } catch (TerminalException e) {
      e.printStackTrace();
      err = e.getErrorMessage();
      isInitialized = false;
    } catch (IllegalStateException ex) {
      ex.printStackTrace();
      err = ex.getMessage();
      isInitialized = true;
    }

    JSObject ret = new JSObject();
    ret.put("isInitialized", isInitialized);

    if (!isInitialized) {
      ret.put("error", err);
      call.error(err);
      return;
    }

    call.resolve(ret);
  }

  @PluginMethod
  public void setConnectionToken(PluginCall call) {
    String token = call.getString("token");
    String errorMessage = call.getString("errorMessage");

    if (pendingConnectionTokenCallback != null) {
      if (errorMessage != null && !errorMessage.trim().isEmpty()) {
        pendingConnectionTokenCallback.onFailure(
          new ConnectionTokenException(errorMessage)
        );
      } else {
        pendingConnectionTokenCallback.onSuccess(token);
      }

      call.resolve();
    }

    pendingConnectionTokenCallback = null;
  }

  @PluginMethod
  public void discoverReaders(final PluginCall call) {
    // Attempt to abort any pending discoverReader calls first.
    abortDiscoverReaders();

    Boolean simulated = call.getBoolean("simulated");
    DeviceType deviceType = DeviceType.values()[call.getInt("deviceType")];

    DiscoveryConfiguration discoveryConfiguration = new DiscoveryConfiguration(
      0,
      deviceType,
      simulated
    );
    Callback statusCallback = new Callback() {

      @Override
      public void onSuccess() {
        pendingDiscoverReaders = null;
        call.resolve();
      }

      @Override
      public void onFailure(@Nonnull TerminalException e) {
        pendingDiscoverReaders = null;
        call.error(e.getErrorMessage());
      }
    };

    abortDiscoverReaders();
    pendingDiscoverReaders =
      Terminal
        .getInstance()
        .discoverReaders(discoveryConfiguration, this, statusCallback);
  }

  @PluginMethod
  public void abortDiscoverReaders(final PluginCall call) {
    if (
      pendingDiscoverReaders != null && !pendingDiscoverReaders.isCompleted()
    ) {
      pendingDiscoverReaders.cancel(
        new Callback() {

          @Override
          public void onSuccess() {
            pendingDiscoverReaders = null;
            call.resolve();
          }

          @Override
          public void onFailure(@Nonnull TerminalException e) {
            call.error(e.getErrorMessage());
          }
        }
      );
    } else {
      call.resolve();
    }
  }

  public void abortDiscoverReaders() {
    if (
      pendingDiscoverReaders != null && !pendingDiscoverReaders.isCompleted()
    ) {
      pendingDiscoverReaders.cancel(
        new Callback() {

          @Override
          public void onSuccess() {
            pendingDiscoverReaders = null;
          }

          @Override
          public void onFailure(@Nonnull TerminalException e) {}
        }
      );
    }
  }

  @PluginMethod
  public void connectReader(final PluginCall call) {
    String serialNumber = call.getString("serialNumber");
    Reader selectedReader = null;
    if (discoveredReadersList != null && discoveredReadersList.size() > 0) {
      for (Reader reader : discoveredReadersList) {
        if (reader != null) {
          if (reader.getSerialNumber().equals(serialNumber)) {
            selectedReader = reader;
          }
        }
      }
    }

    if (selectedReader != null) {
      Terminal
        .getInstance()
        .connectReader(
          selectedReader,
          new ReaderCallback() {

            @Override
            public void onSuccess(@Nonnull Reader reader) {
              JSObject ret = new JSObject();
              ret.put("reader", TerminalUtils.serializeReader(reader));
              call.resolve(ret);
            }

            @Override
            public void onFailure(@Nonnull TerminalException e) {
              call.error(e.getErrorMessage(), e);
            }
          }
        );
    } else {
      call.error("No reader found with provided serial number");
    }
  }

  @PluginMethod
  public void disconnectReader(final PluginCall call) {
    if (Terminal.getInstance().getConnectedReader() == null) {
      call.resolve();
    } else {
      Terminal
        .getInstance()
        .disconnectReader(
          new Callback() {

            @Override
            public void onSuccess() {
              call.resolve();
            }

            @Override
            public void onFailure(@Nonnull TerminalException e) {
              call.error(e.getErrorMessage(), e);
            }
          }
        );
    }
  }

  @PluginMethod
  public void getConnectedReader(PluginCall call) {
    Reader reader = Terminal.getInstance().getConnectedReader();
    JSObject ret = new JSObject();
    ret.put("reader", TerminalUtils.serializeReader(reader));
    call.resolve(ret);
  }

  @PluginMethod
  public void getConnectionStatus(PluginCall call) {
    ConnectionStatus status = Terminal.getInstance().getConnectionStatus();

    JSObject ret = new JSObject();
    ret.put("status", status.ordinal());
    ret.put("isAndroid", true);
    call.resolve(ret);
  }

  @Override
  public void fetchConnectionToken(
    @NotNull ConnectionTokenCallback connectionTokenCallback
  ) {
    pendingConnectionTokenCallback = connectionTokenCallback;

    JSObject ret = new JSObject();
    notifyListeners("requestConnectionToken", ret);
  }

  @Override
  public void onConnectionStatusChange(
    @NotNull ConnectionStatus connectionStatus
  ) {
    JSObject ret = new JSObject();
    ret.put("status", connectionStatus.ordinal());
    ret.put("isAndroid", true);
    notifyListeners("didChangeConnectionStatus", ret);
  }

  @Override
  public void onPaymentStatusChange(@NotNull PaymentStatus paymentStatus) {
    JSObject ret = new JSObject();
    ret.put("status", paymentStatus.ordinal());

    notifyListeners("didChangePaymentStatus", ret);
  }

  @Override
  public void onReportLowBatteryWarning() {
    notifyListeners("didReportLowBatteryWarning", new JSObject());
  }

  @Override
  public void onReportReaderEvent(@NotNull ReaderEvent readerEvent) {
    lastReaderEvent = readerEvent;
    JSObject ret = new JSObject();
    ret.put("event", readerEvent.ordinal());
    notifyListeners("didReportReaderEvent", ret);
  }

  @Override
  public void onUnexpectedReaderDisconnect(@NotNull Reader reader) {
    JSObject ret = new JSObject();
    ret.put("reader", TerminalUtils.serializeReader(reader));
    notifyListeners("didReportUnexpectedReaderDisconnect", ret);
  }

  @Override
  public void onUpdateDiscoveredReaders(@NotNull List<? extends Reader> list) {
    discoveredReadersList = list;

    JSArray readersDiscoveredArr = new JSArray();
    for (Reader reader : list) {
      if (reader != null) {
        readersDiscoveredArr.put(TerminalUtils.serializeReader(reader));
      }
    }

    JSObject ret = new JSObject();
    ret.put("readers", readersDiscoveredArr);

    notifyListeners("readersDiscovered", ret);
  }
}
