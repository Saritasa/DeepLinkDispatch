package com.airbnb.deeplinkdispatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.core.app.TaskStackBuilder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"WeakerAccess", "unused"})
public class BaseDeepLinkDelegate {

  protected static final String TAG = "DeepLinkDelegate";

  protected final List<? extends Parser> loaders;

  public List<? extends Parser> getLoaders() {
    return loaders;
  }

  public BaseDeepLinkDelegate(List<? extends Parser> loaders) {
    this.loaders = loaders;
  }

  private DeepLinkEntry findEntry(String uriString) {
    for (Parser loader : loaders) {
      DeepLinkEntry entry = loader.parseUri(uriString);
      if (entry != null) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Calls into {@link #dispatchFrom(Activity activity, Intent sourceIntent)}.
   *
   * @param activity activity with inbound Intent stored on it.
   * @return DeepLinkResult, whether success or error.
   */
  public DeepLinkResult dispatchFrom(Activity activity) {
    if (activity == null) {
      throw new NullPointerException("activity == null");
    }
    return dispatchFrom(activity, activity.getIntent());
  }

  /**
   * Calls {@link #createResult(Activity, Intent)}. If the DeepLinkResult has
   * a non-null TaskStackBuilder or Intent, it starts it. It always calls
   * {@link #notifyListener(Context, boolean, Uri, String, String)}.
   *
   * @param activity     used to startActivity() or notifyListener().
   * @param sourceIntent inbound Intent.
   * @return DeepLinkResult
   */
  public DeepLinkResult dispatchFrom(Activity activity, Intent sourceIntent) {
    DeepLinkResult result = createResult(activity, sourceIntent);
    if (result.getTaskStackBuilder() != null) {
      result.getTaskStackBuilder().startActivities();
    } else if (result.getIntent() != null) {
      activity.startActivity(result.getIntent());
    }
    notifyListener(activity, !result.isSuccessful(), sourceIntent.getData(),
        result.getDeepLinkEntry().getUriTemplate(), result.error());
    return result;
  }

  /**
   * Create a {@link DeepLinkResult}, whether we are able to match the uri on
   * {@param sourceIntent} or not.
   * @param activity     used to startActivity() or notifyListener().
   * @param sourceIntent inbound Intent.
   * @return DeepLinkResult
   */
  public DeepLinkResult createResult(Activity activity, Intent sourceIntent) {
    if (activity == null) {
      throw new NullPointerException("activity == null");
    }
    if (sourceIntent == null) {
      throw new NullPointerException("sourceIntent == null");
    }
    Uri uri = sourceIntent.getData();
    if (uri == null) {
      return new DeepLinkResult(
          false, null, "No Uri in given activity's intent.", null, null, null);
    }
    String uriString = uri.toString();
    DeepLinkEntry entry = findEntry(uriString);
    if (entry != null) {
      DeepLinkUri deepLinkUri = DeepLinkUri.parse(uriString);
      Map<String, String> parameterMap = entry.getParameters(uriString);
      for (String queryParameter : deepLinkUri.queryParameterNames()) {
        for (String queryParameterValue : deepLinkUri.queryParameterValues(queryParameter)) {
          if (parameterMap.containsKey(queryParameter)) {
            Log.w(TAG, "Duplicate parameter name in path and query param: " + queryParameter);
          }
          parameterMap.put(queryParameter, queryParameterValue);
        }
      }
      parameterMap.put(DeepLink.URI, uri.toString());
      Bundle parameters;
      if (sourceIntent.getExtras() != null) {
        parameters = new Bundle(sourceIntent.getExtras());
      } else {
        parameters = new Bundle();
      }
      for (Map.Entry<String, String> parameterEntry : parameterMap.entrySet()) {
        parameters.putString(parameterEntry.getKey(), parameterEntry.getValue());
      }
      try {
        Class<?> c = entry.getActivityClass();
        Intent newIntent;
        TaskStackBuilder taskStackBuilder = null;
        if (entry.getType() == DeepLinkEntry.Type.CLASS) {
          newIntent = new Intent(activity, c);
        } else {
          Method method;
          DeepLinkResult errorResult = new DeepLinkResult(false, uriString,
              "Could not deep link to method: " + entry.getMethod() + " intents length == 0", null,
              null, entry);
          try {
            method = c.getMethod(entry.getMethod(), Context.class);
            if (method.getReturnType().equals(TaskStackBuilder.class)) {
              taskStackBuilder = (TaskStackBuilder) method.invoke(c, activity);
              if (taskStackBuilder.getIntentCount() == 0) {
                return errorResult;
              }
              newIntent = taskStackBuilder.editIntentAt(taskStackBuilder.getIntentCount() - 1);
            } else {
              newIntent = (Intent) method.invoke(c, activity);
            }
          } catch (NoSuchMethodException exception) {
            method = c.getMethod(entry.getMethod(), Context.class, Bundle.class);
            if (method.getReturnType().equals(TaskStackBuilder.class)) {
              taskStackBuilder = (TaskStackBuilder) method.invoke(c, activity, parameters);
              if (taskStackBuilder.getIntentCount() == 0) {
                return errorResult;
              }
              newIntent = taskStackBuilder.editIntentAt(taskStackBuilder.getIntentCount() - 1);
            } else {
              newIntent = (Intent) method.invoke(c, activity, parameters);
            }
          }
        }
        if (newIntent.getAction() == null) {
          newIntent.setAction(sourceIntent.getAction());
        }
        if (newIntent.getData() == null) {
          newIntent.setData(sourceIntent.getData());
        }
        newIntent.putExtras(parameters);
        newIntent.putExtra(DeepLink.IS_DEEP_LINK, true);
        newIntent.putExtra(DeepLink.REFERRER_URI, uri);
        if (activity.getCallingActivity() != null) {
          newIntent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        }
        return new DeepLinkResult(true, uriString, "", newIntent, taskStackBuilder, entry);
      } catch (NoSuchMethodException exception) {
        return new DeepLinkResult(false, uriString, "Deep link to non-existent method: "
            + entry.getMethod(), null, null, entry);
      } catch (IllegalAccessException exception) {
        return new DeepLinkResult(false, uriString, "Could not deep link to method: "
            + entry.getMethod(), null, null, entry);
      } catch (InvocationTargetException exception) {
        return new DeepLinkResult(false, uriString, "Could not deep link to method: "
            + entry.getMethod(), null, null, entry);
      }
    } else {
      return new DeepLinkResult(false, uriString, "No registered entity to handle deep link: "
          + uri.toString(), null, null, null);
    }

  }

  private static void notifyListener(Context context, boolean isError, Uri uri,
                                     String uriTemplate, String errorMessage) {
    Intent intent = new Intent();
    intent.setAction(DeepLinkHandler.ACTION);
    intent.putExtra(DeepLinkHandler.EXTRA_URI, uri != null ? uri.toString() : "");
    intent.putExtra(DeepLinkHandler.EXTRA_URI_TEMPLATE, uriTemplate != null ? uriTemplate : "");
    intent.putExtra(DeepLinkHandler.EXTRA_SUCCESSFUL, !isError);
    if (isError) {
      intent.putExtra(DeepLinkHandler.EXTRA_ERROR_MESSAGE, errorMessage);
    }
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
  }

  public boolean supportsUri(String uriString) {
    return findEntry(uriString) != null;
  }
}
