package io.zeebe.bpmn.games.slack;

import com.github.seratch.jslack.api.methods.MethodsClient;
import com.github.seratch.jslack.api.methods.SlackApiException;
import com.github.seratch.jslack.api.model.block.ActionsBlock;
import com.github.seratch.jslack.api.model.block.LayoutBlock;
import com.github.seratch.jslack.api.model.block.SectionBlock;
import com.github.seratch.jslack.api.model.block.composition.MarkdownTextObject;
import com.github.seratch.jslack.api.model.block.composition.OptionObject;
import com.github.seratch.jslack.api.model.block.composition.PlainTextObject;
import com.github.seratch.jslack.api.model.block.element.BlockElement;
import com.github.seratch.jslack.api.model.block.element.ButtonElement;
import com.github.seratch.jslack.api.model.block.element.StaticSelectElement;
import com.github.seratch.jslack.app_backend.interactive_messages.response.ActionResponse;
import io.zeebe.bpmn.games.GameInteraction;
import io.zeebe.bpmn.games.model.Card;
import io.zeebe.bpmn.games.model.CardType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SlackUserInteraction implements GameInteraction {

  private final ScheduledExecutorService executorService =
      Executors.newSingleThreadScheduledExecutor();

  @Autowired private SlackSession session;
  @Autowired private MethodsClient methodsClient;

  @Override
  public CompletableFuture<List<Card>> selectCardsToPlay(String player, List<Card> handCards) {

    final var channelId = session.getChannelId(player);

    final var blocks = new ArrayList<LayoutBlock>();

    final var block1 =
        SectionBlock.builder()
            .text(MarkdownTextObject.builder().text("Choose one of the cards to play").build())
            .build();
    blocks.add(block1);

    final var cardButtons = new ArrayList<BlockElement>();

    final var passButton =
        ButtonElement.builder()
            .text(PlainTextObject.builder().text("PASS").build())
            .value("pass")
            .actionId("pass")
            .style("primary")
            .build();

    cardButtons.add(passButton);

    final List<Card> nonPlayableCards = new ArrayList<>(handCards);

    final Map<CardType, List<Card>> catCards =
        handCards.stream()
            .filter(card -> card.getType().isCatCard())
            .collect(Collectors.groupingBy(Card::getType));

    final List<Card> feralCats = catCards.getOrDefault(CardType.FERAL_CAT, List.of());

    final List<Card> nonFeralCats =
        catCards.entrySet().stream()
            .filter(e -> e.getKey() != CardType.FERAL_CAT)
            .map(e -> e.getValue().get(0))
            .collect(Collectors.toList());

    if (feralCats.size() == 1) {
      final var feralCat = feralCats.get(0);
      nonFeralCats.forEach(
          cat -> cardButtons.add(buildActionButton("play_card", List.of(feralCat, cat))));

      nonPlayableCards.removeAll(feralCats);
      nonPlayableCards.removeAll(nonFeralCats);
    }

    final List<List<Card>> twoSameCatCards =
        catCards.entrySet().stream()
            .filter(e -> e.getValue().size() >= 2)
            .map(e -> e.getValue())
            .collect(Collectors.toList());

    twoSameCatCards.forEach(
        cats -> cardButtons.add(buildActionButton("play_card", List.of(cats.get(0), cats.get(1)))));
    twoSameCatCards.forEach(nonPlayableCards::removeAll);

    final List<Card> actionCards =
        handCards.stream()
            .filter(card -> card.getType() != CardType.NOPE && card.getType() != CardType.DEFUSE)
            .filter(card -> !card.getType().isCatCard())
            .collect(Collectors.toList());

    actionCards.forEach(card -> cardButtons.add(buildActionButton("play_card", List.of(card))));
    nonPlayableCards.removeAll(actionCards);

    final var block2 = ActionsBlock.builder().elements(cardButtons).build();
    blocks.add(block2);

    if (!nonPlayableCards.isEmpty()) {

      final var block3 =
          SectionBlock.builder()
              .text(
                  MarkdownTextObject.builder()
                      .text(
                          String.format(
                              "Not playable cards: %s",
                              nonPlayableCards.stream()
                                  .map(card -> String.format("*%s*", card.getType().name()))
                                  .collect(Collectors.joining(", "))))
                      .build())
              .build();

      blocks.add(block3);
    }

    try {

      final var resp = methodsClient.chatPostMessage(req -> req.channel(channelId).blocks(blocks));

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    final var future = new CompletableFuture<List<Card>>();

    session.putPendingAction(
        channelId,
        action -> {
          final String actionValue = action.getValue();

          final List<Card> cards;
          if (actionValue.equals("pass")) {
            cards = List.of();
          } else {
            cards = getCardsFromActionValue(actionValue);
          }

          future.complete(cards);

          return ActionResponse.builder()
              .responseType("ephemeral")
              // .text(String.format("Action '%s' has been accepted.", action.getActionId()))
              .deleteOriginal(true)
              // .replaceOriginal(false)
              .build();
        });

    return future;
  }

  @Override
  public CompletableFuture<Boolean> nopeThePlayedCard(String player) {
    final var channelId = session.getChannelId(player);

    final var block1 =
        SectionBlock.builder()
            .text(MarkdownTextObject.builder().text("Do you want to NOPE the card?").build())
            .build();

    final var nopeButton =
        ButtonElement.builder()
            .text(PlainTextObject.builder().text("NOPE!").build())
            .value("nope")
            .actionId("nope")
            .style("primary")
            .build();

    final var skipButton =
        ButtonElement.builder()
            .text(PlainTextObject.builder().text("Skip").build())
            .value("skip")
            .actionId("skip")
            .build();

    final var block2 = ActionsBlock.builder().elements(List.of(nopeButton, skipButton)).build();

    final var future = new CompletableFuture<Boolean>();

    try {

      final var resp =
          methodsClient.chatPostMessage(
              req -> req.channel(channelId).blocks(List.of(block1, block2)));

      final var messageTs = resp.getTs();

      executorService.schedule(
          () -> removeMessage(future, channelId, messageTs), 5, TimeUnit.SECONDS);

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    session.putPendingAction(
        channelId,
        action -> {
          final String actionValue = action.getValue();

          if (actionValue.equals("nope")) {
            future.complete(true);
          } else {
            future.complete(false);
          }

          return ActionResponse.builder().responseType("ephemeral").deleteOriginal(true).build();
        });

    return future;
  }

  @Override
  public CompletableFuture<List<Card>> alterTheFuture(String player, List<Card> cards) {
    final var channelId = session.getChannelId(player);

    final var block1 =
        SectionBlock.builder()
            .text(MarkdownTextObject.builder().text("Select the future you like").build())
            .build();

    final var permutations = permutations(cards);
    final var possibleFutures = distinct(permutations);

    final List<BlockElement> futureButtons =
        possibleFutures.stream()
            .map(f -> buildActionButton("alter_the_future", f))
            .collect(Collectors.toList());

    final var block2 = ActionsBlock.builder().elements(futureButtons).build();

    try {

      final var resp =
          methodsClient.chatPostMessage(
              req -> req.channel(channelId).blocks(List.of(block1, block2)));

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    final var future = new CompletableFuture<List<Card>>();

    session.putPendingAction(
        channelId,
        action -> {
          final List<Card> alteredFuture = getCardsFromActionValue(action.getValue());
          future.complete(alteredFuture);

          return ActionResponse.builder().responseType("ephemeral").deleteOriginal(true).build();
        });

    return future;
  }

  @Override
  public CompletableFuture<String> selectPlayer(String player, List<String> otherPlayers) {
    final var channelId = session.getChannelId(player);

    final var blocks = new ArrayList<LayoutBlock>();

    blocks.add(
        SectionBlock.builder()
            .text(MarkdownTextObject.builder().text("Choose a player to draw a card from:").build())
            .build());

    otherPlayers.stream()
        .forEach(
            otherPlayer -> {
              final var text =
                  MarkdownTextObject.builder().text(SlackUtil.formatPlayer(otherPlayer)).build();

              final var button =
                  ButtonElement.builder()
                      .text(PlainTextObject.builder().text("Choose").build())
                      .value(otherPlayer)
                      .actionId("select_player" + otherPlayer)
                      .build();

              final var playerBlock =
                  SectionBlock.builder().text(text).accessory(button).build();

              blocks.add(playerBlock);
            });

    final var future = new CompletableFuture<String>();

    try {

      final var resp = methodsClient.chatPostMessage(req -> req.channel(channelId).blocks(blocks));

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    session.putPendingAction(
        channelId,
        action -> {
          final String selectedPlayer = action.getValue();
          future.complete(selectedPlayer);

          return ActionResponse.builder().responseType("ephemeral").deleteOriginal(true).build();
        });

    return future;
  }

  @Override
  public CompletableFuture<Card> selectCardToGive(String player, List<Card> handCards) {
    final var channelId = session.getChannelId(player);

    final var block1 =
        SectionBlock.builder()
            .text(MarkdownTextObject.builder().text("Choose a card to give away:").build())
            .build();

    final List<BlockElement> cardButtons =
        handCards.stream()
            .map(card -> buildActionButton("select_card", List.of(card)))
            .collect(Collectors.toList());

    final var block2 = ActionsBlock.builder().elements(cardButtons).build();

    final var future = new CompletableFuture<Card>();

    try {

      final var resp =
          methodsClient.chatPostMessage(
              req -> req.channel(channelId).blocks(List.of(block1, block2)));

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    session.putPendingAction(
        channelId,
        action -> {
          final var selectedCard = getCardsFromActionValue(action.getValue()).get(0);
          future.complete(selectedCard);

          return ActionResponse.builder().responseType("ephemeral").deleteOriginal(true).build();
        });

    return future;
  }

  @Override
  public CompletableFuture<Integer> selectPositionToInsertCard(
      String player, Card card, int deckSize) {
    final var channelId = session.getChannelId(player);

    final var text =
        String.format(
            "Choose where to insert %s in the deck (%d cards):",
            SlackUtil.formatCard(card), deckSize);

    final var block1 =
        SectionBlock.builder().text(MarkdownTextObject.builder().text(text).build()).build();

    final var options = new ArrayList<OptionObject>();

    options.add(
        OptionObject.builder()
            .text(PlainTextObject.builder().text("top").build())
            .value(String.valueOf(0))
            .build());

    options.add(
        OptionObject.builder()
            .text(PlainTextObject.builder().text("bottom").build())
            .value(String.valueOf(deckSize))
            .build());

    if (deckSize > 1) {
      options.add(
          OptionObject.builder()
              .text(PlainTextObject.builder().text("random").build())
              .value(String.valueOf(ThreadLocalRandom.current().nextInt(deckSize)))
              .build());
    }

    IntStream.range(1, deckSize)
        .forEach(
            index -> {
              options.add(
                  OptionObject.builder()
                      .text(
                          PlainTextObject.builder()
                              .text(String.format("top %d card", 1 + index))
                              .build())
                      .value(String.valueOf(index))
                      .build());
            });

    final var positionSelect =
        StaticSelectElement.builder()
            .options(options)
            .placeholder(PlainTextObject.builder().text("position").build())
            .actionId("select_card")
            .build();

    final var block2 = ActionsBlock.builder().elements(List.of(positionSelect)).build();

    final var future = new CompletableFuture<Integer>();

    try {

      final var resp =
          methodsClient.chatPostMessage(
              req -> req.channel(channelId).blocks(List.of(block1, block2)));

    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }

    session.putPendingAction(
        channelId,
        action -> {
          final var actionValue = action.getSelectedOption().getValue();
          final var index = Integer.parseInt(actionValue);
          future.complete(index);

          return ActionResponse.builder().responseType("ephemeral").deleteOriginal(true).build();
        });

    return future;
  }

  private <T> List<List<T>> permutations(List<T> list) {
    if (list.isEmpty()) {
      return List.of(list);
    } else if (list.size() == 1) {
      return List.of(list.subList(0, 1));
    } else if (list.size() == 2) {
      final var reverseList = new ArrayList<>(list.subList(0, 2));
      Collections.reverse(reverseList);
      return List.of(list, reverseList);
    } else {
      final var result = new ArrayList<List<T>>();

      for (int i = 0; i < list.size(); i++) {

        final var other = new ArrayList<>(list);
        final T element = other.remove(i);

        final List<List<T>> ps = new ArrayList<>(permutations(other));
        for (int j = 0; j < ps.size(); j++) {
          final List<T> p = new ArrayList<>(ps.get(j));
          p.add(0, element);

          result.add(p);
        }
      }

      return result;
    }
  }

  private List<List<Card>> distinct(List<List<Card>> list) {
    final List<List<Card>> result = new ArrayList<>();

    final Function<List<Card>, List<CardType>> f =
        l -> l.stream().map(Card::getType).collect(Collectors.toList());

    list.forEach(
        l -> {
          if (result.stream().noneMatch(r -> f.apply(r).equals(f.apply(l)))) {
            result.add(l);
          }
        });

    return result;
  }

  private void removeMessage(
      CompletableFuture<Boolean> future, String channelId, String messageTs) {

    if (future.isDone()) {
      return;
    }

    try {
      methodsClient.chatDelete(req -> req.channel(channelId).ts(messageTs));
    } catch (SlackApiException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ButtonElement buildActionButton(String action, List<Card> cards) {
    final var plainText =
        cards.stream().map(card -> card.getType().name()).collect(Collectors.joining(" & "));

    final var value =
        cards.stream()
            .map(card -> String.format("%d-%s", card.getId(), card.getType()))
            .collect(Collectors.joining("+"));

    final var text = PlainTextObject.builder().text(plainText).build();

    return ButtonElement.builder().text(text).value(value).actionId(action + "-" + value).build();
  }

  private List<Card> getCardsFromActionValue(String actionValue) {
    return Arrays.stream(actionValue.split("\\+"))
        .map(
            value -> {
              final var cardIdAndType = value.split("-");
              final var cardId = cardIdAndType[0];
              final var cardType = cardIdAndType[1];

              return new Card(Integer.parseInt(cardId), CardType.valueOf(cardType));
            })
        .collect(Collectors.toList());
  }
}
