package io.zeebe.casino.action;

import io.zeebe.client.api.response.ActivatedJob;
import io.zeebe.client.api.worker.JobClient;
import io.zeebe.client.api.worker.JobHandler;
import org.slf4j.Logger;

public class SeeAction implements JobHandler {

  public SeeAction(Logger log) {
  }

  @Override
  public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {

  }
}
