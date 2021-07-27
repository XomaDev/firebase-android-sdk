// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appdistribution;

import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.AUTHENTICATION_CANCELED;
import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.AUTHENTICATION_FAILURE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabsIntent;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appdistribution.Constants.ErrorMessages;
import com.google.firebase.installations.FirebaseInstallationsApi;
import java.util.List;

class TesterSignInClient {
  private static final String TAG = "FADSignInTester";

  private TaskCompletionSource<Void> signInTaskCompletionSource = null;
  private CancellationTokenSource signInCancellationSource;
  private final String SIGNIN_REDIRECT_URL =
      "https://appdistribution.firebase.google.com/pub/testerapps/%s/installations/%s/buildalerts?appName=%s&packageName=%s";
  private final FirebaseApp firebaseApp;
  private final FirebaseInstallationsApi firebaseInstallationsApi;

  TesterSignInClient(
      @NonNull FirebaseApp firebaseApp,
      @NonNull FirebaseInstallationsApi firebaseInstallationsApi) {
    this.firebaseApp = firebaseApp;
    this.firebaseInstallationsApi = firebaseInstallationsApi;
  }

  @NonNull
  public Task<Void> signInTester(@NonNull Activity currentActivity) {
    if (this.isCurrentlySigningIn()) {
      signInCancellationSource.cancel();
    }

    signInCancellationSource = new CancellationTokenSource();
    signInTaskCompletionSource = new TaskCompletionSource<>(signInCancellationSource.getToken());

    AlertDialog alertDialog = getSignInAlertDialog(currentActivity);
    alertDialog.show();

    return signInTaskCompletionSource.getTask();
  }

  boolean isCurrentlySigningIn() {
    return signInTaskCompletionSource != null && !signInTaskCompletionSource.getTask().isComplete();
  }

  void setCanceledAuthenticationError() {
    setSignInTaskCompletionError(
        new FirebaseAppDistributionException(
            Constants.ErrorMessages.AUTHENTICATION_CANCELED, AUTHENTICATION_CANCELED));
  }

  void setSuccessfulSignInResult() {
    signInTaskCompletionSource.setResult(null);
  }

  private AlertDialog getSignInAlertDialog(Activity currentActivity) {
    AlertDialog alertDialog = new AlertDialog.Builder(currentActivity).create();
    Context context = firebaseApp.getApplicationContext();
    alertDialog.setTitle(context.getString(R.string.signin_dialog_title));
    alertDialog.setMessage(context.getString(R.string.singin_dialog_message));
    alertDialog.setButton(
        AlertDialog.BUTTON_POSITIVE,
        context.getString(R.string.singin_yes_button),
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int i) {
            firebaseInstallationsApi
                .getId()
                .addOnSuccessListener(getFidGenerationOnSuccessListener(currentActivity))
                .addOnFailureListener(
                    new OnFailureListener() {
                      @Override
                      public void onFailure(@NonNull Exception e) {
                        setSignInTaskCompletionError(
                            new FirebaseAppDistributionException(
                                Constants.ErrorMessages.AUTHENTICATION_ERROR,
                                AUTHENTICATION_FAILURE,
                                e));
                      }
                    });
          }
        });
    alertDialog.setButton(
        AlertDialog.BUTTON_NEGATIVE,
        context.getString(R.string.singin_no_button),
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int i) {
            setSignInTaskCompletionError(
                new FirebaseAppDistributionException(
                    ErrorMessages.AUTHENTICATION_CANCELED, AUTHENTICATION_CANCELED));
            dialogInterface.dismiss();
          }
        });
    return alertDialog;
  }

  private void setSignInTaskCompletionError(FirebaseAppDistributionException e) {
    if (signInTaskCompletionSource != null && !signInTaskCompletionSource.getTask().isComplete()) {
      signInTaskCompletionSource.setException(e);
    }
  }

  private OnSuccessListener<String> getFidGenerationOnSuccessListener(Activity currentActivity) {
    return new OnSuccessListener<String>() {
      @Override
      public void onSuccess(String fid) {
        Context context = firebaseApp.getApplicationContext();
        Uri uri =
            Uri.parse(
                String.format(
                    SIGNIN_REDIRECT_URL,
                    firebaseApp.getOptions().getApplicationId(),
                    fid,
                    getApplicationName(context),
                    context.getPackageName()));
        openSignInFlowInBrowser(currentActivity, uri);
      }
    };
  }

  private static String getApplicationName(Context context) {
    try {
      return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
    } catch (Exception e) {
      Log.e(TAG, "Unable to retrieve App name");
      return "";
    }
  }

  private void openSignInFlowInBrowser(Activity currentActivity, Uri uri) {
    if (supportsCustomTabs(firebaseApp.getApplicationContext())) {
      // If we can launch a chrome view, try that.
      CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder().build();
      Intent intent = customTabsIntent.intent;
      intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      customTabsIntent.launchUrl(currentActivity, uri);

    } else {
      // If we can't launch a chrome view try to launch anything that can handle a URL.
      Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
      browserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
      browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      currentActivity.startActivity(browserIntent);
    }
  }

  private boolean supportsCustomTabs(Context context) {
    Intent customTabIntent = new Intent("android.support.customtabs.action.CustomTabsService");
    customTabIntent.setPackage("com.android.chrome");
    List<ResolveInfo> resolveInfos =
        context.getPackageManager().queryIntentServices(customTabIntent, 0);
    return resolveInfos != null && !resolveInfos.isEmpty();
  }
}
