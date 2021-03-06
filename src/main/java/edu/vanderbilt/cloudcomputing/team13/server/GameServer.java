package edu.vanderbilt.cloudcomputing.team13.server;

import edu.vanderbilt.cloudcomputing.team13.client.GameState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zeromq.ZMQ;
import edu.vanderbilt.cloudcomputing.team13.client.GameBoard;
import edu.vanderbilt.cloudcomputing.team13.util.AbstractAction;
import edu.vanderbilt.cloudcomputing.team13.util.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;


/**
 * Created by Chuilian Kong on 10/30/2017.
 */
public class GameServer implements Runnable{
    private static final Logger logger = LogManager.getLogger(GameServer.class.getName());

    // this context is shared among all zmq sockets in this app
    private ZMQ.Context context;
    // ip address of host machine
    private String ip;
    // list of ports
    private String responderPort;
    private String publisherPort;

    // a publisher to every player
    private ZMQ.Socket gamePub;
    // a responder to every player
    private ZMQ.Socket gameResponder;
    // a list of players <id, player>
    private GameState gameState = new GameState();

    // thread pool
    private ExecutorService threadPool;
    // request handler map <request name, action>
    private HashMap<String, AbstractAction> requestHandler;

    private boolean isSeverStop = false;


    // game specs
    private int MAX_PLAYER = 5;
    private boolean isGameEnd = true;

    public GameServer(String ip){
        this.ip = ip;
        context = ZMQ.context(1);
        gamePub = context.socket(ZMQ.PUB);
        gameResponder = context.socket(ZMQ.REP);

        responderPort = "5555";
        publisherPort = "5556";

        threadPool = Executors.newFixedThreadPool(2 + MAX_PLAYER * 2);

        initRequestHandler();
    }

    @Override
    public void run(){
        gameResponder.bind("tcp://*:" + responderPort);
        gamePub.bind("tcp://*:" + publisherPort);
        logger.info("*** Server is running.");
        threadPool.submit(this::responder);
        while(!isSeverStop){
            try{
                Thread.sleep(1000 * 10);
            }catch (InterruptedException e){
                break;
            }
        }
        gameResponder.close();
        gamePub.close();
        context.term();
    }

    public void stop(){
        isSeverStop = true;
    }

    private void responder(){
        while(!Thread.currentThread().isInterrupted()){
            //  Wait for next request from client
            String reqStr = gameResponder.recvStr();
            if(reqStr == null){
                logger.fatal("received null");
            }
            logger.debug("request received: {}", reqStr);
            tryRespond(reqStr);
        }
    }

    private void tryRespond(String reqStr){
        String[] splited = reqStr.split("@");
        AbstractAction handler = requestHandler.get(splited[0]);
        if(handler == null){
            logger.warn("invalid request");
            gameResponder.send("ERROR");
            logger.debug("replied req: {}, ERROR", reqStr);
        }else{
            gameResponder.send("OK");
            logger.debug("replied req: {}, OK", reqStr);
            handler.setPara(splited[1]);
            threadPool.submit(handler);
        }
    }

    private void initRequestHandler(){
        requestHandler = new HashMap<>();
        requestHandler.put("CliNewPoint", new publishNewPoint());
        requestHandler.put("CliNewWinner", new publishNewWinner());
        requestHandler.put("CliNewPlayer", new setupNewPlayer());
        requestHandler.put("CliPlayerReady", new setPlayerReady());
    }

    private class publishNewPoint extends AbstractAction{
        String para = null;

        @Override
        public void setPara(String para) {
            this.para = para;
        }

        @Override
        public void run(){
            if(para == null){
                logger.warn("invalid parameter for publishNewPoint.");
            }else{
                logger.debug("publishing new point: {}", para);
                gamePub.sendMore("ServerNewPoint");
                gamePub.send(para);
            }
        }
    }

    private class publishNewWinner extends AbstractAction{
        String para = null;

        @Override
        public void setPara(String para) {
            this.para = para;
        }

        @Override
        public void run(){
            if(para == null){
                logger.warn("invalid parameter for publishNewWinner.");
            }else{
                // double check if this game is still no winner
                if(!isGameEnd){
                    gamePub.sendMore("ServerNewWinner");
                    gamePub.send(para);
                }
            }
        }
    }

    private class setupNewPlayer extends AbstractAction {
        String para = null;

        @Override
        public void setPara(String para) {
            this.para = para;
        }

        @Override
        public void run() {
            if (para == null) {
                logger.warn("invalid parameter for setupNewPlayer.");
            } else {
                // check if there is enough space
                if (gameState.getCurPlayerNum() <= MAX_PLAYER) {
                    // add new player to list
                    String[] splited = para.split("%");
                    String playerId = splited[0];
                    String playerIp = splited[1];
                    String playerName = splited[2];
                    String position = Integer.toString(gameState.getCurPlayerNum());
                    gameState.addPlayer(playerIp, playerName, playerId, position);

                    // sent list to all players
                    StringBuilder para = new StringBuilder();
                    // compile the new para string
                    for(Map.Entry<String, Player> entry : gameState.getPlayersMap().entrySet()){
                        Player player = entry.getValue();
                        String curInfo = player.getId() + "%" + player.getIp() + "%" + player.getName() + "%" + player.getPosition();
                        para.append("|");
                        para.append(curInfo);
                    }
                    para.deleteCharAt(0);

                    gamePub.sendMore("ServerNewPlayerList");
                    gamePub.send(para.toString());
                }
            }
        }
    }

    private class setPlayerReady extends AbstractAction {
        String para = null;

        @Override
        public void setPara(String para) {
            this.para = para;
        }

        @Override
        public void run() {
            if (para == null) {
                logger.warn("invalid parameter for setPlayerReady.");
            } else {
                // if the game is on-going, omit this one.
                if(!isGameEnd) return;

                String[] splited = para.split("%");
                int readyState = Integer.parseInt(splited[1]);
                gameState.setPlayerReady(splited[0], readyState != 0);
                gamePub.sendMore("ServerPlayerReady");
                gamePub.send(para);
                // if all players are ready, start the game
                for(Map.Entry<String, Player> entry : gameState.getPlayersMap().entrySet()){
                    if(!entry.getValue().isReady()){
                        return;
                    }
                }

                // start the game
                isGameEnd = false;
                // randomly pick a drawer and a word
                int playerNum = gameState.getCurPlayerNum();
                int randomNum = ThreadLocalRandom.current().nextInt(0, playerNum);
                String pickedPlayerID = null;
                for(Map.Entry<String, Player> entry : gameState.getPlayersMap().entrySet()) {
                    randomNum--;
                    if(randomNum < 0){
                        pickedPlayerID = entry.getKey();
                    }
                }
                String pickedWord = "FindMeUnays";

                gamePub.sendMore("ServerNewGame");
                gamePub.send(pickedPlayerID + "%"+ pickedWord);
            }
        }
    }

    public static void main(String[] args) {
        new GameServer(args[0]).run();
    }

}
