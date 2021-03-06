// Copyright 2018 Google LLC
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

package com.google.uicd.backend.core.uicdactions;

import com.google.uicd.backend.core.devicesdriver.AndroidDeviceDriver;
import com.google.uicd.backend.core.exceptions.UicdDeviceException;
import com.google.uicd.backend.core.uicdactions.ActionContext.PlayMode;
import com.google.uicd.backend.core.uicdactions.ActionContext.PlayStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** CompoundAction is a collection of actions, a DAG with single entry point */
public class CompoundAction extends BaseAction implements Cloneable {

  public List<BaseAction> childrenActions = new ArrayList<>();
  public List<String> childrenIdList = new ArrayList<>();
  private int repeatTime = 1;
  private boolean failAtTheEnd = false;

  @Override
  public String getDisplay() {
    if (repeatTime == 1) {
      return getName();
    } else {
      return String.format("%s: Repeat %d times", getName(), repeatTime);
    }
  }

  @Override
  public ActionExecutionResult playWithDelay(
      List<AndroidDeviceDriver> deviceDrivers, ActionContext actionContext)
      throws UicdDeviceException {
    int deviceIndex =
        actionContext.getPlayMode() == PlayMode.SINGLE
            ? actionContext.getCurrentDeviceIndex()
            : getDeviceIndex();
    return this.playWithDelay(deviceDrivers, actionContext, deviceIndex);
  }

  @Override
  public ActionExecutionResult playWithDelay(
      List<AndroidDeviceDriver> deviceDrivers, ActionContext actionContext, int deviceIndex)
      throws UicdDeviceException {
    boolean playbackCancelled = false;
    ActionExecutionResult actionExecutionResult = new ActionExecutionResult();
    actionExecutionResult.setSequenceIndex(actionContext.getNextActionSequenceIndex());
    actionExecutionResult.setRegularOutput(this.getDisplay());
    actionExecutionResult.setOutputType(ActionExecutionResult.OutputType.COMPOUND);
    actionExecutionResult.setActionId(this.getActionId().toString());
    logActionStart();

    playStatus = ActionContext.PlayStatus.READY;
    boolean stopCurrentLevel = false;

    for (int i = 0; i < repeatTime; i++) {
      for (BaseAction action : childrenActions) {
        if (stopCurrentLevel) {
          action.playStatus = PlayStatus.SKIPPED;
        } else {
          // Reset the playStatus. Action is always in memory, we need to clear the status before
          // running the action. TODO(b/112010063): Move playStatus outside action.
          action.playStatus = PlayStatus.READY;
        }

        // For the compound action, we are still using the abstract deviceDriver, since for multi
        // device case, compound action might need control different device
        if (actionContext.playbackStopRequested()) {
          playbackCancelled = true;
          ActionExecutionResult childResult = new ActionExecutionResult();
          childResult.setRegularOutput(action.getDisplay());
          childResult.setSequenceIndex(actionContext.getNextActionSequenceIndex());
          childResult.setActionId(action.getActionId().toString());
          childResult.setPlayStatus(ActionContext.PlayStatus.CANCELLED);
          actionExecutionResult.addChildResult(childResult);
        } else {
          ActionExecutionResult childResult;
          // for multi-device, child action has to figure out the index by itself.
          if (actionContext.getPlayMode() == PlayMode.MULTIDEVICE) {
            childResult = action.playWithDelay(deviceDrivers, actionContext);
          } else {
            childResult = action.playWithDelay(deviceDrivers, actionContext, deviceIndex);
          }
          actionExecutionResult.addChildResult(childResult);
          if (action.playStatus == PlayStatus.EXIT_CURRENT_COMPOUND) {
            stopCurrentLevel = true;
          }
        }
      }
      // wait for single repeat
      waitAfter(actionContext);
    }
    logActionEnd();
    for (ActionExecutionResult childRes : actionExecutionResult.getChildrenResult()) {
      if (childRes.getPlayStatus() == ActionContext.PlayStatus.FAIL) {
        actionExecutionResult.setPlayStatus(ActionContext.PlayStatus.FAIL);
        this.playStatus = ActionContext.PlayStatus.FAIL;
      }
    }
    if (this.playStatus == ActionContext.PlayStatus.READY) {
      if (playbackCancelled) {
        this.playStatus = ActionContext.PlayStatus.CANCELLED;
        actionExecutionResult.setPlayStatus(ActionContext.PlayStatus.CANCELLED);
      } else {
        this.playStatus = ActionContext.PlayStatus.PASS;
        actionExecutionResult.setPlayStatus(ActionContext.PlayStatus.PASS);
      }
    }
    return actionExecutionResult;
  }

  public void removeAction(String uuidStr) {
    if (!childrenActions.removeIf(action -> action.getActionId().toString().equals(uuidStr))) {
      logger.warning("Error! Trying to remove action that is not in sequence: " + uuidStr);
    }
    if (!childrenIdList.removeIf(uuid -> uuid.equals(uuidStr))) {
      logger.warning("Error! Trying to remove action that is not in sequence - " + uuidStr);
    }
  }

  @Override
  public void updateAction(BaseAction baseAction) {
    super.updateBaseAction(baseAction);

    if (baseAction instanceof CompoundAction) {
      CompoundAction otherAction = (CompoundAction) baseAction;
      this.repeatTime = otherAction.repeatTime;
      this.failAtTheEnd = otherAction.failAtTheEnd;

      // reorder current action list based on list order of input
      HashMap<UUID, BaseAction> map = new HashMap<>();
      for (BaseAction childAction : childrenActions) {
        if (childAction == null) {
          System.out.println("child action is null");
        } else {
          map.put(childAction.getActionId(), childAction);
        }
      }
      childrenIdList = otherAction.childrenIdList;
      childrenActions = new ArrayList<>();
      for (String childId : childrenIdList) {
        childrenActions.add(map.get(UUID.fromString(childId)));
      }
    }
  }

  // Prevent user from adding action to itself, uicd won't crash, but will have some low level
  // error, which is annoying. Use this, also can make sure the workflow is a "tree" instead of
  // graph.
  private boolean checkCycling(String parentId, BaseAction child) {
    if (parentId.equals(child.getActionId().toString())) {
      return true;
    }
    if (child instanceof CompoundAction) {
      CompoundAction compoundChild = (CompoundAction) child;
      for (BaseAction grandchild : compoundChild.childrenActions) {
        if (checkCycling(parentId, grandchild)) {
          return true;
        }
      }
    }
    return false;
  }

  public void addAction(BaseAction action) {
    if (checkCycling(this.getActionId().toString(), action)) {
      logger.warning(
          String.format(
              "Cycling reference. Can not add child Action: %s", action.getActionId().toString()));
      return;
    }
    childrenActions.add(action);
    childrenIdList.add(action.getActionId().toString());
  }

  @Override
  protected int play(AndroidDeviceDriver androidDeviceDriver, ActionContext actionContext) {
    /*
     * Compound action is a special action that already override playwithdelay, itself doesn't
     * have any real action to
     * play. Leave the play as empty.
     */
    return 0;
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    CompoundAction newAction = (CompoundAction) super.clone();
    newAction.setActionId(UUID.randomUUID());
    newAction.childrenActions = new ArrayList<>(this.childrenActions);
    newAction.childrenIdList = new ArrayList<>(this.childrenIdList);
    return newAction;
  }
}
