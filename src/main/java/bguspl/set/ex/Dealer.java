package bguspl.set.ex;

import bguspl.set.Env;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**\
     * if a legal set was claimed it will be represented here
     * if not the fellUps is null
     */
    private List<Integer> legalSet;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * unchecked sets claimed by users 
     * sets are represented as an Integer array of size featureSize + 1, where the first value is the player id and the remaining are the slots claimed
     * added by us
     */
    private BlockingQueue<Integer> setClaims;
    
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        legalSet = null;
        setClaims = new ArrayBlockingQueue<Integer>(players.length);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (Player p: players){
            Thread player = new Thread(p);
            player.start();
        }
        while (!shouldFinish()) {
            table.writeLock();
            removeAllCardsFromTable();
            placeCardsOnTable();
            table.writeUnlock();
            timerLoop();
            updateTimerDisplay(true); 
        }
        terminate();
        removeAllCardsFromTable();
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            table.writeLock();
            updateTimerDisplay(checkSet());
            removeCardsFromTable();
            placeCardsOnTable();
            if (env.config.hints == true){
                table.hints();
            }
            table.writeUnlock();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        table.writeUnlock();
        if (!terminate){
            for (Player p:players){ 
                p.terminate();
            }
            terminate = true;
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        List<Integer> cards = new LinkedList<Integer>();
        for (Integer card : table.slotToCard){
            if (card != null){
                cards.add(card);
            }
        }
        for (Integer card : deck){
            cards.add(card);
        }
        return terminate || env.util.findSets(cards, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (legalSet != null){
            for (Integer slot : legalSet){
                for (Player p :players){
                    boolean removed = p.removeToken(slot);
                    if (removed){
                        setClaims.remove(p.id);
                        p.waitsForCheck = false;
                        synchronized (p){
                            p.notifyAll(); 
                        }
                    }
                }
                if(table.slotToCard[slot]!=null){
                    table.removeCard(slot);
                }
            }
            legalSet = null;
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int slot = 0; slot < env.config.tableSize; slot++){
            if (table.slotToCard[slot] == null){
                placeRandomCard(slot);
            }   
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        
            synchronized (this) {
                try {
                    wait(1);
                } catch (InterruptedException ignored) {
                }
            }
        
        
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        long timeLeft = reshuffleTime-System.currentTimeMillis();
        if (timeLeft < 0){
            env.ui.setCountdown(0,0 <= env.config.turnTimeoutWarningMillis );
        }
        env.ui.setCountdown(timeLeft,timeLeft <= env.config.turnTimeoutWarningMillis );
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for( Player p: players){
            setClaims.remove(p.id);
            p.waitsForCheck=false;
            synchronized (p){
                p.notifyAll(); 
            }
        }
        for (int slot = 0 ; slot < env.config.tableSize ; slot++){
            for (Player p :players){
                p.removeToken(slot);
            }
            if(table.slotToCard[slot]!=null){
                deck.add(table.slotToCard[slot]);
                table.removeCard(slot);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        List<Integer> winnerList = new LinkedList<Integer>();
        int[] winners = null;
        for (Player p:players){
            if (p.score() > maxScore){
                maxScore = p.score();
                winnerList = new LinkedList<Integer>();
                winnerList.add(p.id);
            } else if (p.score() == maxScore){
                winnerList.add(p.id);
            }
        }
        winners = new int[winnerList.size()];
        int i = 0;
        for (Integer winner : winnerList){
            winners[i] = winner;
            i++;
        }
        env.ui.announceWinner(winners);
    }

    /**
     * added by us
     * checks if a set is legal and acts accordingly
     * @param player - the player that claimed the set
     * @param cards
     */
    public void claimSet(int player){
        players[player].waitsForCheck=true;
        setClaims.add(player);
        synchronized (this){
            notifyAll();
        }
    }

    /**
     * added by us
     * checks the oldest unchecked set and acts accordingly
     */
    private boolean checkSet(){
        Integer playerChecked = setClaims.poll();
        if (playerChecked != null){
            List<Integer> tokens = new LinkedList<Integer>(players[playerChecked].tokens);
            int[] cards = new int[tokens.size()];
            int i = 0;
            for (Integer slot : tokens){
                cards[i] = table.slotToCard[slot];
                i++;
            }
            players[playerChecked].waitsForCheck = false;
            synchronized (players[playerChecked]){
                players[playerChecked].notifyAll(); 
            }
            if (env.util.testSet(cards)){
                players[playerChecked].point();
                legalSet = tokens;
                return true;
            } else {
                players[playerChecked].penalty();
                return false;
            }
            
        }
        playerChecked = null;
        return false;
    }

    /**
     * added by us
     * @param slot
     * @return the card that was placed
     */
    private int placeRandomCard (int slot){
        if (!deck.isEmpty()){
            int index = (int)(Math.random()*(deck.size()));
            int card = deck.remove(index);
            table.placeCard(card, slot);
            return card;
        } else {
            return -1;
        }
        
    }
}
