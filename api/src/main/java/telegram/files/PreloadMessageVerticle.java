package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.Optional;

public class PreloadMessageVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final int HISTORY_SCAN_INTERVAL = 30 * 1000;

    private static final int MAX_HISTORY_SCAN_TIME = 10 * 1000;

    private final SettingAutoRecords autoRecords;

    public PreloadMessageVerticle() {
        this.autoRecords = AutoRecordsHolder.INSTANCE.autoRecords();
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initEventConsumer()
                .onSuccess(r -> {
                    vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL,
                            id -> autoRecords.getPreloadEnabledItems()
                                    .stream()
                                    .filter(auto -> auto.isNotComplete(SettingAutoRecords.HISTORY_PRELOAD_STATE))
                                    .forEach(auto -> addHistoryMessage(auto, System.currentTimeMillis())));

                    log.info("""
                            Preload message verticle started!
                            |Auto chats: %s
                            """.formatted(autoRecords.getPreloadEnabledItems().size()));

                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        log.info("Preload message verticle stopped!");
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), message -> {
            log.trace("Auto download message received: %s".formatted(message.body()));
            this.onNewMessage((JsonObject) message.body());
        });
        return Future.succeededFuture();
    }

    private void addHistoryMessage(SettingAutoRecords.Item auto, long currentTimeMillis) {
        log.debug("Start load history message! TelegramId: %d ChatId: %d".formatted(auto.telegramId, auto.chatId));
        if (System.currentTimeMillis() - currentTimeMillis > MAX_HISTORY_SCAN_TIME) {
            log.debug("Load history message timeout! TelegramId: %d ChatId: %d".formatted(auto.telegramId, auto.chatId));
            return;
        }

        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(auto.telegramId);
        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.chatId = auto.chatId;
        searchChatMessages.fromMessageId = auto.nextFromMessageIdForPreload;
        searchChatMessages.limit = 100;
        TdApi.FoundChatMessages foundChatMessages = Future.await(telegramVerticle.client.execute(searchChatMessages)
                .onFailure(r -> log.error("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(auto.telegramId, auto.chatId), r))
        );
        if (foundChatMessages == null || foundChatMessages.messages.length == 0) {
            log.debug("%s No more history message found! TelegramId: %d ChatId: %d".formatted(auto.uniqueKey(), auto.telegramId, auto.chatId));
            auto.complete(SettingAutoRecords.HISTORY_PRELOAD_STATE);
            return;
        }
        int count = 0;
        for (TdApi.Message message : foundChatMessages.messages) {
            Optional<TdApiHelp.FileHandler<? extends TdApi.MessageContent>> fileHandlerOptional = TdApiHelp.getFileHandler(message);
            if (fileHandlerOptional.isEmpty()) {
                continue;
            }
            FileRecord fileRecord = fileHandlerOptional.get().convertFileRecord(auto.telegramId);
            if (Future.await(DataVerticle.fileRepository.createIfNotExist(fileRecord))) {
                count++;
            }
        }

        if (log.isDebugEnabled() && count > 0) {
            log.debug("Load history message success! TelegramId: %d ChatId: %d Count: %d".formatted(auto.telegramId, auto.chatId, count));
        }
        auto.nextFromMessageIdForPreload = foundChatMessages.nextFromMessageId;
        addHistoryMessage(auto, currentTimeMillis);
    }

    private void onNewMessage(JsonObject jsonObject) {
        long telegramId = jsonObject.getLong("telegramId");
        long chatId = jsonObject.getLong("chatId");
        long messageId = jsonObject.getLong("messageId");
        autoRecords.getPreloadEnabledItems().stream()
                .filter(item -> item.telegramId == telegramId && item.chatId == chatId)
                .findFirst()
                .flatMap(ignore -> TelegramVerticles.get(telegramId))
                .ifPresent(telegramVerticle -> {
                    if (!telegramVerticle.authorized) return;

                    telegramVerticle.client.execute(new TdApi.GetMessage(chatId, messageId))
                            .onSuccess(message -> TdApiHelp.getFileHandler(message).ifPresent(fileHandler -> {
                                FileRecord fileRecord = fileHandler.convertFileRecord(telegramId);
                                DataVerticle.fileRepository.createIfNotExist(fileRecord);
                            }))
                            .onFailure(e -> log.error("Preload message fail. Get message failed: %s".formatted(e.getMessage())));
                });
    }
}
