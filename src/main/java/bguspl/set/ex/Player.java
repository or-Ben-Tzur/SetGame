package bguspl.set.ex;
import bguspl.set.Env;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * added by us
     * actions queue
     */
    public List<Integer> tokens;

    /**
     * added by us
     */
    private BlockingQueue <Integer> keyPresses;
    /**
     * added by us
     */
    private Dealer dealer;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;


    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * indicates whether the player waits for an answer
     */
    public boolean waitsForCheck;
    
    /**
     * added by us
     * time when penalty is over
     */
    private Long freezeTime;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        tokens = new LinkedList<Integer>();
        keyPresses = new ArrayBlockingQueue<>(env.config.featureSize);
        waitsForCheck = false;
        freezeTime = System.currentTimeMillis() - 1;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        playerThread= Thread.currentThread();
        if (!human) createArtificialIntelligence();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }

        while (!terminate) {
            if (waitsForCheck){
                WaitUntilNotify();
            }
            
            updateFreezeTime();

            makeAction();
        }
        
        if (!human){
            aiThread.interrupt();
            try { aiThread.join(); } catch (InterruptedException ignored) {}
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    

    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                synchronized(this){
                    while (keyPresses.size()>=env.config.featureSize || waitsForCheck ){
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    int slot =(int)(Math.random()*env.config.tableSize);
                    keyPressed(slot);
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        waitsForCheck = false;
        freezeTime = System.currentTimeMillis() - 1;
        table.readUnlock();
        playerThread.interrupt();
        try {
            playerThread.join();
        } catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (freezeTime < System.currentTimeMillis()){
            keyPresses.offer(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    
    public void point() {
        freezeTime = System.currentTimeMillis() + env.config.pointFreezeMillis;
        updateFreezeTime();

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freezeTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        updateFreezeTime();
    }

    public int score() {
        return score;
    }

    /**
     * added by us
     * removes the token from the tokens list
     * @param slot
     */
    public boolean removeToken(Integer slot){
        boolean removed = tokens.remove(slot);
        if (removed){
            table.removeToken(id, slot);
        }
        return removed;
    }

    public synchronized void WaitUntilNotify(){    
        while (waitsForCheck || freezeTime > System.currentTimeMillis()){
            try {
                wait(300);
            } catch (InterruptedException ignored) {
            }
            updateFreezeTime();
        }
    }

    private void makeAction (){
        Integer slot = keyPresses.poll();
        synchronized (this){
            notifyAll();
        }
        if (slot == null || table.slotToCard[slot] == null){
            return;
        }
        boolean tokenExists = false;
        for (Integer i:tokens){
            if(i==slot){
                tokenExists = true;
                break;
            }
        }
        if (tokenExists){
            table.readLock();
            removeToken(slot);
            table.readUnlock();
        } else {
            if (tokens.size()<env.config.featureSize){
                Integer card = table.slotToCard[slot];
                table.readLock();
                if (card == table.slotToCard[slot]){
                    table.placeToken(id, slot);
                    tokens.add(slot);
                    if (tokens.size() == env.config.featureSize){//all tokens were placed
                        dealer.claimSet(id);
                        keyPresses.clear();
                    }
                }
                table.readUnlock();
            }
        }
    }

    private void updateFreezeTime(){
        
        env.ui.setFreeze(id, freezeTime - System.currentTimeMillis());
        
    }

    
}
