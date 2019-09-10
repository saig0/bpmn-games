package io.zeebe.bpmn.games;

import io.zeebe.bpmn.games.action.AddTurns;
import io.zeebe.bpmn.games.action.CheckForDefuse;
import io.zeebe.bpmn.games.action.CleanUpAfterExploding;
import io.zeebe.bpmn.games.action.EndTurn;
import io.zeebe.bpmn.games.action.NewTurn;
import io.zeebe.bpmn.games.action.SelectPlayer;
import io.zeebe.bpmn.games.action.ThrowMessage;
import io.zeebe.bpmn.games.action.TransferCard;
import io.zeebe.bpmn.games.action.UpdateDeck;
import io.zeebe.bpmn.games.deck.BuildDeck;
import io.zeebe.bpmn.games.deck.DiscardCards;
import io.zeebe.bpmn.games.deck.DiscardNope;
import io.zeebe.bpmn.games.deck.DrawBottomCard;
import io.zeebe.bpmn.games.deck.DrawTopCard;
import io.zeebe.bpmn.games.deck.InitGame;
import io.zeebe.bpmn.games.user.Celebration;
import io.zeebe.bpmn.games.user.ChangeOrder;
import io.zeebe.bpmn.games.user.InjectKitten;
import io.zeebe.bpmn.games.user.NopeAction;
import io.zeebe.bpmn.games.user.SelectAction;
import io.zeebe.bpmn.games.user.SelectCardFromPlayer;
import io.zeebe.bpmn.games.user.SelectOtherPlayer;
import io.zeebe.bpmn.games.user.SelectRandomCard;
import io.zeebe.bpmn.games.user.ShowTopThree;
import io.zeebe.bpmn.games.user.ShuffleDeck;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.worker.JobHandler;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GamesApplication {

  private static final Logger LOG = LoggerFactory.getLogger(GamesApplication.class);

  private final ZeebeClient client;
  private final GameListener listener;
  private final GameInteraction interaction;

  public GamesApplication(ZeebeClient client, GameListener listener,
      GameInteraction interaction) {
    this.client = client;
    this.listener = listener;
    this.interaction = interaction;
  }

  public void start() {
    LOG.info("Deploy workflows");

    client.newDeployCommand().addResourceFromClasspath("explodingKittens.bpmn").send().join();

    LOG.info("Start workers");

    // general
    installWorkers(
        Map.of(
            "initGame", new InitGame(listener),
            "build-deck", new BuildDeck(listener),
            "selectPlayerForNewRound", new SelectPlayer(listener),
            "discard", new DiscardCards(listener),
            "discardNope", new DiscardNope(listener),
            "addTurns", new AddTurns(listener),
            "endTurn", new EndTurn(listener),
            "newTurn", new NewTurn(),
            "checkForDefuse", new CheckForDefuse(listener)));

    // deck based
    installWorkers(
        Map.of(
            "build-deck",
            new BuildDeck(listener),
            "discard",
            new DiscardCards(listener),
            "cleanUpAfterExploding",
            new CleanUpAfterExploding(listener),
            "drawBottomCard",
            new DrawBottomCard(listener),
            "drawTopCard",
            new DrawTopCard(listener),
            "injectKitten",
            new InjectKitten(listener, interaction)));

    // actions
    installWorkers(
        Map.of(
            "updateDeck", new UpdateDeck(listener),
            "transferCard", new TransferCard(listener),
            "shuffle", new ShuffleDeck(listener)));

    installWorkers(Map.of("throwMessage", new ThrowMessage(client)));

    // user
    installWorkers(
        Map.of(
            "showTopThreeCards", new ShowTopThree(listener),
            "changeOrder", new ChangeOrder(listener, interaction),
            "selectAction", new SelectAction(listener, interaction),
            "selectOtherPlayer", new SelectOtherPlayer(listener, interaction),
            "selectCardFromPlayer", new SelectCardFromPlayer(listener, interaction),
            "chooseRandomCard", new SelectRandomCard(listener),
            "celebrate", new Celebration(listener),
            "play-nope", new NopeAction(listener, interaction)));
  }

  public long startNewGame(Collection<String> playerNames) {

    final var workflowInstance = client
        .newCreateInstanceCommand()
        .bpmnProcessId("exploding-kittens")
        .latestVersion()
        .variables(Map.of("playerNames", playerNames))
        .send()
        .join();

    return workflowInstance.getWorkflowInstanceKey();
  }

  private void installWorkers(Map<String, JobHandler> jobTypeHandlers) {
    for (var jobTypeHandler : jobTypeHandlers.entrySet()) {
      client
          .newWorker()
          .jobType(jobTypeHandler.getKey())
          .handler(jobTypeHandler.getValue())
          .timeout(Duration.ofMinutes(10))
          .requestTimeout(Duration.ofHours(1))
          .open();
    }
  }
}
