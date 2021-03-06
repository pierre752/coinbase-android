package com.coinbase.android.merchant;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.coinbase.android.CoinbaseActivity;
import com.coinbase.android.CoinbaseFragment;
import com.coinbase.android.Constants;
import com.coinbase.android.FontManager;
import com.coinbase.android.MainActivity;
import com.coinbase.android.R;
import com.coinbase.android.Utils;
import com.coinbase.api.LoginManager;
import com.coinbase.api.RpcManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;

import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class PointOfSaleFragment extends Fragment implements CoinbaseFragment {

  private class CreateButtonTask extends AsyncTask<String, Void, Object> {

    @Override
    protected Object doInBackground(String... strings) {

      String amount = strings[0];
      String currency = strings[1];
      String title = strings[2];

      if (title == null || title.trim().equals("")) {
        title = "Android point of sale transaction";
      }

      List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();

      params.add(new BasicNameValuePair("button[name]", title));
      params.add(new BasicNameValuePair("button[description]", title));
      params.add(new BasicNameValuePair("button[price_string]", amount));
      params.add(new BasicNameValuePair("button[price_currency_iso]", currency));
      params.add(new BasicNameValuePair("button[custom]", "coinbase_android_point_of_sale"));

      try {
        JSONObject response = RpcManager.getInstance().callPost(mParent, "buttons", params).optJSONObject("button");
        String button = response.optString("code");

        System.out.println(response.toString(5));

        JSONObject orderResponse = RpcManager.getInstance().callPost(mParent,
                "buttons/" + button + "/create_order", new ArrayList<BasicNameValuePair>());

        return orderResponse;

      } catch (Exception e) {

        e.printStackTrace();
        return e;
      }
    }

    @Override
    protected void onPostExecute(Object o) {

      mCreatingTask = null;

      if (o == null) {

        showResult(null, R.string.pos_result_failure_creation, null);
      } else if (o instanceof Exception) {

        showResult(null, mParent.getString(R.string.pos_result_failure_creation_exception, ((Exception) o).getMessage()), null);
      } else {

        JSONObject result = (JSONObject) o;

        if (!result.optBoolean("success")) {

          showResult(null, mParent.getString(R.string.pos_result_failure_creation_exception, result.toString()), null);
        } else {

          try {
            startAccepting(result.getJSONObject("order"));
          } catch (JSONException e) {

            showResult(null, mParent.getString(R.string.pos_result_failure_creation_exception, e.getMessage()), null);
          }
        }
      }
    }
  }

  private class LoadMerchantInfoTask extends AsyncTask<Void, Void, Object[]> {

    @Override
    protected Object[] doInBackground(Void... arg0) {

      try {
        // 1. Load merchant info
        JSONObject response = RpcManager.getInstance().callGet(mParent, "users");
        JSONObject userInfo = response.getJSONArray("users").getJSONObject(0).getJSONObject("user");
        JSONObject merchantInfo = userInfo.getJSONObject("merchant");

        // 2. if possible, load logo
        if (merchantInfo.optJSONObject("logo") != null) {
         try {

           String logoUrlString = merchantInfo.getJSONObject("logo").getString("small");
           URL logoUrl = logoUrlString.startsWith("/") ? new URL(new URL(LoginManager.CLIENT_BASEURL), logoUrlString) : new URL(logoUrlString);
           Bitmap logo = BitmapFactory.decodeStream(logoUrl.openStream());
           return new Object[] { merchantInfo, logo };
         } catch (Exception e) {
           // Could not load logo
           e.printStackTrace();
           return new Object[] { merchantInfo, null };
         }
        } else {
          // No logo
          return new Object[] { merchantInfo, null };
        }

      } catch (Exception e) {
        // Could not load merchant info
        e.printStackTrace();
        return null;
      }
    }

    @Override
    protected void onPostExecute(Object[] result) {

      if (result == null) {

        // Data could not be loaded.
        for (View header : mHeaders) {
          header.setVisibility(View.GONE);
        }
      } else {

        for (View header : mHeaders) {
          header.setVisibility(View.VISIBLE);
        }

        String title = ((JSONObject) result[0]).optString("company_name");
        for (TextView titleView : mHeaderTitles) {
          titleView.setText(title);
          titleView.setGravity(result[1] != null ? Gravity.RIGHT : Gravity.CENTER);
        }


        for (ImageView logoView : mHeaderLogos) {
          if (result[1] != null) {
            logoView.setVisibility(View.VISIBLE);
            logoView.setImageBitmap((Bitmap) result[1]);
          } else {
            logoView.setVisibility(View.GONE);
          }
        }
      }
    }
  }

  private enum CheckStatusState {
    SUCCESS,
    FAILURE,
    DONE;
  }

  private class CheckStatusTask extends TimerTask {

    private Context mContext;
    private TextView mStatus = null;
    private Handler mHandler;
    private String mOrderId;

    private int mTimesExecuted = 0;
    private JSONObject mOrder = null;

    public CheckStatusTask(Context context, TextView statusView, String orderId) {

      mContext = context;
      mStatus = statusView;
      mHandler = new Handler();
      mOrderId = orderId;
    }

    public void run() {

      mTimesExecuted++;
      final CheckStatusState state = doCheck();
      if(state == CheckStatusState.DONE) {

        // TODO cancel timer
        paymentAccepted(mOrder);
      } else {

        // Update status indicator
        mHandler.post(new Runnable() {

          public void run() {

            int textColor, bgColor;
            String text;
            if (state == CheckStatusState.SUCCESS) {
              textColor = Color.BLACK;
              bgColor = mParent.getResources().getColor(R.color.pos_waiting_good);
              text = getString(R.string.pos_accept_waiting);

              int index = (mTimesExecuted % 3) + 1;
              text = text.substring(0, text.length() - index) + " " + text.substring(text.length() - index);
            } else {
              textColor = Color.WHITE;
              bgColor = mParent.getResources().getColor(R.color.pos_waiting_bad);
              text = getString(R.string.pos_accept_waiting_error);
            }

            mStatus.setTextColor(textColor);
            mStatus.setBackgroundColor(bgColor);
            mStatus.setText(text);
          }
        });
      }
    }

    private CheckStatusState doCheck() {
      try {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
        String currentUserId = prefs.getString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), null);

        JSONObject response = RpcManager.getInstance().callGet(mContext, "orders/" + mOrderId);
        if(response.optJSONObject("order") != null) {

          mOrder = response.optJSONObject("order");
          return CheckStatusState.DONE;
        } else {
          // Successful check, but the order isn't in yet.
          return CheckStatusState.SUCCESS;
        }

      } catch (Exception e) {
        // Check was a failure - make sure to alert the user.
        e.printStackTrace();
        return CheckStatusState.FAILURE;
      }
    }
  }

  private static final int INDEX_MAIN = 0;
  private static final int INDEX_LOADING = 1;
  private static final int INDEX_ACCEPT = 2;
  private static final int INDEX_RESULT = 3;
  private static final int CHECK_PERIOD = 2000;

  private MainActivity mParent;
  private ToneGenerator mToneGenerator;

  private EditText mAmount, mNotes;
  private Button mSubmit;
  private Spinner mCurrency;
  private String[] mCurrenciesArray;
  private ViewFlipper mFlipper;

  private ImageView mAcceptQr;
  private TextView mAcceptDesc;
  private TextView mAcceptStatus;
  private Button mAcceptCancel;
  private Button mLoadingCancel;

  private TextView mResultStatus, mResultMessage;
  private Button mResultOK;

  private View[] mHeaders;
  private TextView[] mHeaderTitles;
  private ImageView[] mHeaderLogos;
  private ImageView mMenuButton;

  private Timer mCheckStatusTimer = null;
  private CreateButtonTask mCreatingTask = null;

  @Override
  public void onSwitchedTo() {

    if (mFlipper.getDisplayedChild() == INDEX_MAIN) {
      mAmount.requestFocus();
    }

    ((CoinbaseActivity) mParent).getSupportActionBar().hide();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mToneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
  }

  @Override
  public void onPINPromptSuccessfulReturn() {

  }

  private void switchToMain() {

    mFlipper.setDisplayedChild(INDEX_MAIN);
    mAmount.requestFocus();
    setKeyboardVisible(true);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

    // Inflate the layout for this fragment
    View view = inflater.inflate(R.layout.fragment_point_of_sale, container, false);

    mAmount = (EditText) view.findViewById(R.id.pos_amt);
    mNotes = (EditText) view.findViewById(R.id.pos_notes);
    mSubmit = (Button) view.findViewById(R.id.pos_submit);
    mCurrency = (Spinner) view.findViewById(R.id.pos_currency);
    mFlipper = (ViewFlipper) view.findViewById(R.id.pos_flipper);

    mAcceptCancel = (Button) view.findViewById(R.id.pos_accept_cancel);
    mAcceptQr = (ImageView) view.findViewById(R.id.pos_accept_qr);
    mAcceptDesc = (TextView) view.findViewById(R.id.pos_accept_desc);
    mAcceptStatus = (TextView) view.findViewById(R.id.pos_accept_waiting);
    mLoadingCancel = (Button) view.findViewById(R.id.pos_loading_cancel);

    mResultStatus = (TextView) view.findViewById(R.id.pos_result_status);
    mResultMessage = (TextView) view.findViewById(R.id.pos_result_msg);
    mResultOK = (Button) view.findViewById(R.id.pos_result_ok);

    mSubmit.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));
    mAcceptCancel.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));
    mLoadingCancel.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));
    mResultOK.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));

    DisplayMetrics metrics = getResources().getDisplayMetrics();
    int smallestWidth = Math.min(metrics.widthPixels, metrics.heightPixels);
    int qrSize = smallestWidth - (int) (100 * metrics.density);
    mAcceptQr.getLayoutParams().height = mAcceptQr.getLayoutParams().width = qrSize;

    mMenuButton = (ImageView) view.findViewById(R.id.pos_menu);
    mMenuButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        mParent.openOptionsMenu();
      }
    });

    mLoadingCancel.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        if (mFlipper.getDisplayedChild() != INDEX_LOADING) return;
        mCreatingTask.cancel(true);
        switchToMain();
      }
    });

    mSubmit.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        if (mFlipper.getDisplayedChild() != INDEX_MAIN) return;
        if ("".equals(mAmount.getText().toString())) {
          Toast.makeText(mParent, R.string.pos_empty_amount, Toast.LENGTH_SHORT).show();
          return;
        }

        mFlipper.setDisplayedChild(INDEX_LOADING);
        mCreatingTask = new CreateButtonTask();
        mCreatingTask.execute(mAmount.getText().toString(),
                (String) mCurrency.getSelectedItem(), mNotes.getText().toString());
        mAmount.setText(null);
      }
    });
    mAcceptCancel.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {

        if (mFlipper.getDisplayedChild() != INDEX_ACCEPT) return;
        stopAccepting();
      }
    });
    mResultOK.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (mFlipper.getDisplayedChild() != INDEX_RESULT) return;
        switchToMain();
      }
    });
    
    initializeCurrencySpinner();
    mCurrency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

      @Override
      public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {

        updateAmountHint();
      }

      @Override
      public void onNothingSelected(AdapterView<?> arg0) {
        // Ignore
      }});

    // Headers
    int[] headers = { R.id.pos_accept_header, R.id.pos_main_header, R.id.pos_result_header };

    mHeaders = new View[headers.length];
    mHeaderTitles = new TextView[headers.length];
    mHeaderLogos = new ImageView[headers.length];
    for (int i = 0; i < headers.length; i++) {

      mHeaders[i] = view.findViewById(headers[i]);
      mHeaderTitles[i] = (TextView) mHeaders[i].findViewById(R.id.pos_header_name);
      mHeaderLogos[i] = (ImageView) mHeaders[i].findViewById(R.id.pos_header_logo);

      mHeaderTitles[i].setText(null);
      mHeaderLogos[i].setImageDrawable(null);
    }

    return view;
  }

  private void initializeCurrencySpinner() {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String nativeCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount),
        "usd").toUpperCase(Locale.CANADA);

    mCurrenciesArray = new String[] {
                                     "BTC",
                                     nativeCurrency,
    };

    ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
        mParent, R.layout.fragment_transfer_currency, Arrays.asList(mCurrenciesArray)) {

      @Override
      public View getView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setText(mCurrenciesArray[position]);
        return view;
      }

      @Override
      public View getDropDownView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        view.setText(mCurrenciesArray[position]);
        return view;
      }
    };
    arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    mCurrency.setAdapter(arrayAdapter);
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);

    mParent = (MainActivity) activity;
  }

  private void startAccepting(JSONObject order) throws JSONException {

    String receiveAddress = order.getString("receive_address");
    String amount = moneyToValue(order.getJSONObject("total_btc")).toPlainString();
    String bitcoinUri = String.format("bitcoin:%1$s?amount=%2$s", receiveAddress, amount);
    String orderId = order.getString("id");

    String amountString = Utils.formatCurrencyAmount(amount) + " BTC";
    if (!order.getJSONObject("total_native").getString("currency_iso").equals("BTC")) {
      amountString += String.format(" (%1$s %2$s)", Utils.formatCurrencyAmount(
              moneyToValue(order.getJSONObject("total_native")),
              false,
              Utils.CurrencyType.TRADITIONAL), order.getJSONObject("total_native").getString("currency_iso"));
    }

    Bitmap bitmap;
    try {
      bitmap = Utils.createBarcode(bitcoinUri, BarcodeFormat.QR_CODE, 512, 512);
    } catch (WriterException e) {
      e.printStackTrace();
      bitmap = null;
    }
    mAcceptQr.setImageBitmap(bitmap);
    mAcceptDesc.setText(getString(R.string.pos_accept_message, amountString));

    mCheckStatusTimer = new Timer();
    mCheckStatusTimer.schedule(new CheckStatusTask(mParent, mAcceptStatus, orderId), CHECK_PERIOD, CHECK_PERIOD);

    mFlipper.setDisplayedChild(INDEX_ACCEPT);
    setKeyboardVisible(false);
  }

  private void stopAccepting() {

    mCheckStatusTimer.cancel();
    mCheckStatusTimer = null;
    showResult("cancelled", R.string.pos_result_failure_cancel, null);
  }

  private void paymentAccepted(final JSONObject order) {

    mCheckStatusTimer.cancel();
    mCheckStatusTimer = null;

    mParent.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        showResult(order.optString("status"), null, order);
      }
    });
  }

  private void showResult(String status, int message, JSONObject order) {
    showResult(status, mParent.getString(message), order);
  }

  private void showResult(String status, String _message, JSONObject order) {

    if (status == null) {
      status = "ERROR";
    } else {
      status = status.toUpperCase(Locale.CANADA);
    }

    CharSequence message;
    int color;
    int tone = ToneGenerator.TONE_DTMF_1;
    if ("COMPLETED".equals(status)) {

      String orderId = order.optString("id");
      String amount = Utils.formatCurrencyAmount(moneyToValue(order.optJSONObject("total_native")), false, Utils.CurrencyType.TRADITIONAL);
      String currency = order.optJSONObject("total_native").optString("currency_iso").toUpperCase(Locale.CANADA);
      message = Html.fromHtml(String.format("<b>Order %1$s</b><br>%2$s", orderId, getString(R.string.pos_result_completed, amount, currency)));
      color = R.color.pos_result_completed;
      tone = ToneGenerator.TONE_DTMF_9;
    } else if ("MISPAID".equals(status)) {
      message = getString(R.string.pos_result_mispaid);
      color = R.color.pos_result_mispaid;
    } else {
      color = R.color.pos_result_error;
      message = _message;
    }

    float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, mParent.getResources().getDisplayMetrics());
    ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
            new float[] { radius, radius, radius, radius, radius, radius, radius, radius }, null, null));
    background.getPaint().setColor(mParent.getResources().getColor(color));
    mResultStatus.setBackgroundDrawable(background);

    mResultStatus.setText(status);
    mResultMessage.setText(message);
    mFlipper.setDisplayedChild(INDEX_RESULT);
    setKeyboardVisible(false);
    ((Vibrator) mParent.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(500);
    // mToneGenerator.startTone(tone, 500);
  }

  private BigDecimal moneyToValue(JSONObject money) {

    String currency = money.optString("currency_iso");
    BigDecimal cents = new BigDecimal(money.optString("cents"));
    BigDecimal result;
    if ("BTC".equals(currency)) {
      result = cents.multiply(new BigDecimal(0.00000001), MathContext.DECIMAL128);
    } else {
      result = cents.multiply(new BigDecimal(0.01), MathContext.DECIMAL128);
    }
    return result.setScale(10, BigDecimal.ROUND_HALF_UP).stripTrailingZeros();
  }

  private void setKeyboardVisible(boolean visible) {

    InputMethodManager inputMethodManager = (InputMethodManager) mParent.getSystemService(Context.INPUT_METHOD_SERVICE);

    if(visible) {
      inputMethodManager.showSoftInput(mAmount, InputMethodManager.SHOW_FORCED);
    } else {
      inputMethodManager.hideSoftInputFromWindow(mParent.findViewById(android.R.id.content).getWindowToken(), 0);
    }
  }

  @Override
  public void onStart() {
    super.onStart();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String notes = prefs.getString(String.format(Constants.KEY_ACCOUNT_POS_NOTES, activeAccount), "");
    boolean btcPrices = prefs.getBoolean(String.format(Constants.KEY_ACCOUNT_POS_BTC_AMT, activeAccount), false);
    mNotes.setText(notes);
    mCurrency.setSelection(btcPrices ? 0 : 1);
  }

  @Override
  public void onStop() {
    super.onStop();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    Editor e = prefs.edit();
    e.putString(String.format(Constants.KEY_ACCOUNT_POS_NOTES, activeAccount), mNotes.getText().toString());
    e.putBoolean(String.format(Constants.KEY_ACCOUNT_POS_BTC_AMT, activeAccount), mCurrency.getSelectedItemPosition() == 0);
    e.commit();
  }
  
  private void updateAmountHint() {

    // Update text hint
    String currency = mCurrenciesArray[mCurrency.getSelectedItemPosition()];
    mAmount.setHint(String.format(getString(R.string.pos_amt), currency.toUpperCase(Locale.CANADA)));
  }

  public void refresh() {

    new LoadMerchantInfoTask().execute();
  }
}
