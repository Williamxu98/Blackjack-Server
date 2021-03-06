package gameplay;

import java.util.ArrayList;

import connection.Client;
import connection.Player;
import connection.Server;
import utilities.ClientList;

/**
 * Handles the actual gameplay, i.e. which player's turn is it, giving the
 * dealer cards, etc.
 * 
 * @author Bimesh De Silva, Patrick Liu, William Xu, Barbara Guo
 * @version December 1, 2015
 */
public class Dealer implements Runnable {

	/**
	 * Characters representing the suits of the card deck: spades, clubs,
	 * hearts, and diamonds.
	 */
	public static final char[] SUITS = { 'S', 'C', 'H', 'D' };

	/**
	 * Characters representing the various ranks of the cards in the deck. <br>
	 * Note that "10" is represented but 'T' to remain consistent with the
	 * single character format and allow for the use of a char array instead of
	 * a string array.
	 */
	public static final char[] RANKS = { 'A', '2', '3', '4', '5', '6', '7',
			'8', '9', 'T', 'J', 'Q', 'K' };

	/**
	 * The number of decks (52 cards per deck) in play after each shuffle.
	 */
	public static final int NUMBER_OF_DECKS = 6;

	/**
	 * Minimum cards required in the deck before the start of the round.
	 */
	public static final int MINIMUM_CARDS_PER_PLAYER = 21;

	/**
	 * Seconds provided for clients to place their bets.
	 */
	public static final int BETTING_TIME = 60;

	/**
	 * Chance that the deck will be shuffled at the end of a round (in
	 * percentage). Setting this to 100 means the deck will be shuffled after
	 * every turn.
	 */
	public static final int SHUFFLE_CHANCE = 20;

	private Server server;
	private Deck deck;
	private ClientList players;

	/**
	 * The cards in the dealer's current hand.
	 */
	private ArrayList<Card> dealerCards;

	/**
	 * The value of all the cards in the dealer's current hand.
	 */
	private int dealerHand;

	/**
	 * Whether player's can place a bet. Object is used to allow for
	 * synchronization.
	 */
	private Boolean bettingIsActive;

	/**
	 * The player number of the player who should make their choice to hit,
	 * stand, or doubledown.
	 */
	private int currentPlayerTurn;

	private BettingTimer betTimer;
	private Thread betTimerThread;

	/**
	 * The timer for the betting period before each round. Runs for the number
	 * of seconds specified in the {@link Dealer#BETTING_TIME} constant
	 */
	private class BettingTimer implements Runnable {
		@Override
		public void run() {
			// Wait x seconds and end the betting time, x being 'BETTING_TIME'
			int bettingElapsedTime = 0;
			while (Dealer.this.bettingIsActive) {
				try {
					Thread.sleep(Server.MESSAGE_DELAY);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				bettingElapsedTime += Server.MESSAGE_DELAY;

				if (bettingElapsedTime >= BETTING_TIME * 1000) {
					synchronized (Dealer.this.bettingIsActive) {
						Dealer.this.bettingIsActive = false;
					}
				}
			}
		}
	}

	/**
	 * Constructor for a new Dealer object. Starts up the actual main game of
	 * Blackjack.
	 * 
	 * @param server
	 *            the server that the game is in.
	 * @param players
	 *            a list of all of the player.
	 */
	public Dealer(Server server, ClientList players) {
		this.deck = new Deck(NUMBER_OF_DECKS);
		this.server = server;
		this.players = players;
		this.dealerHand = 0;
		this.dealerCards = new ArrayList<Card>();
		this.bettingIsActive = false;

		// Send this dealer object to all the players
		for (Client player : players) {
			player.setDealer(this);
		}

		// Place the betting timer in the thread
		this.betTimer = new BettingTimer();
		this.betTimerThread = new Thread(this.betTimer);
	}

	/**
	 * Begins the game.
	 */
	public void run() {
		while (this.server.gameStarted()) {
			// Broadcast that a new round has started
			this.server.queueMessage("% NEWROUND");
			this.server.println("Starting new round...");

			// Give players 60 seconds to place their bets, and reset all their
			// previous bets
			this.server.println("Betting starts now...");
			this.bettingIsActive = true;
			this.betTimerThread = new Thread(new BettingTimer());
			this.betTimerThread.start();
			for (Client player : this.players) {
				if (player.isPlayer()) {
					player.setBet(0);
				}
			}

			// Continually check if everyone has placed a bet, and stop after 30
			// seconds
			while (this.bettingIsActive) {
				boolean allBet = true;
				for (Client currentPlayer : this.players) {
					if (currentPlayer.isPlayer() && currentPlayer.getBet() == 0) {
						allBet = false;
					}
				}
				if (allBet) {
					synchronized (Dealer.this.bettingIsActive) {
						this.bettingIsActive = false;
					}
				}
				try {
					Thread.sleep(Server.MESSAGE_DELAY);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			try {
				this.betTimerThread.join();
			} catch (InterruptedException e1) {
			}

			// Disconnect all players who haven't bet
			for (Client currentPlayer : this.players) {
				if (currentPlayer.isPlayer() && currentPlayer.getBet() == 0) {
					currentPlayer.disconnect();
				}
			}

			// Broadcast the dealer's cards and add them to the dealer's hand
			Card hiddenCard = this.deck.getCard();
			this.server.queueMessage("# 0 X X");
			Card cardDrawn = this.deck.getCard();
			this.dealTheDealer(cardDrawn);
			this.server.queueMessage("# 0 " + cardDrawn.toString());

			// Go through each player and deal them two cards each
			for (Client player : this.players) {
				for (int card = 0; card < 2; card++) {
					cardDrawn = this.deck.getCard();
					player.getPlayer().addCard(cardDrawn);
					this.server.queueMessage("# " + (player.getPlayerNo())
							+ " " + cardDrawn.toString());

				}
			}

			// Goes through each client for their turn
			for (Client currentPlayer : this.players) {
				this.currentPlayerTurn = currentPlayer.getPlayerNo();
				boolean endTurn = false;

				// Check if player has blackjack from first two cards
				if (currentPlayer.getPlayer().getHandValue() == 21) {
					int newCoins = currentPlayer.getCoins()
							+ currentPlayer.getBet();
					currentPlayer.setCoins(newCoins);
					currentPlayer.setBet(0);
					this.server.queueMessage("& " + currentPlayer.getPlayerNo()
							+ " blackjack " + newCoins);
					endTurn = true;
					currentPlayer.getPlayer().setCurrentMove('N');
				}

				// Set the turn to the current player and tell all players
				while (currentPlayer.isPlayer() && this.server.gameStarted()
						&& !endTurn) {
					currentPlayer.getPlayer().setCurrentMove('N');
					this.server.queueMessage("% " + (this.currentPlayerTurn)
							+ " turn");

					char currentMove = 'N';

					// Wait for a response from the player
					while (currentPlayer.isPlayer()
							&& (currentMove = currentPlayer.getPlayer()
									.getCurrentMove()) == 'N') {
						try {
							Thread.sleep(Server.MESSAGE_DELAY);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}

					if (currentMove == 'H') {
						// Hit
						// Draw a new card and give it to the player
						cardDrawn = this.deck.getCard();
						this.server.queueMessage("# "
								+ (currentPlayer.getPlayerNo()) + " "
								+ cardDrawn.toString());
						currentPlayer.getPlayer().addCard(cardDrawn);

						if (currentPlayer.getPlayer().getHandValue() > 21) {
							// If the player bust, remove their bet from their
							// coins
							// Broadcast to the server that the player bust
							// End the player's turn
							int newCoins = currentPlayer.getCoins()
									- currentPlayer.getBet();
							currentPlayer.setCoins(newCoins);
							currentPlayer.setBet(0);
							this.server.queueMessage("& "
									+ currentPlayer.getPlayerNo() + " bust "
									+ newCoins);
							endTurn = true;
						} else if (currentPlayer.getPlayer().getHandValue() == 21) {
							// If the player got blackjack, add their bet to
							// their coins
							// Broadcast to the server that the player got a
							// blackjack
							// End the player's turn
							int newCoins = currentPlayer.getCoins()
									+ currentPlayer.getBet();
							currentPlayer.setCoins(newCoins);
							currentPlayer.setBet(0);
							this.server.queueMessage("& "
									+ currentPlayer.getPlayerNo()
									+ " blackjack " + newCoins);
							endTurn = true;
						}
					} else if (currentMove == 'S') {
						// Stand
						// End the player's turn
						this.server.queueMessage("& "
								+ currentPlayer.getPlayerNo() + " stand "
								+ currentPlayer.getCoins());
						endTurn = true;
					} else if (currentMove == 'D') {
						// If the client doubles down, double their bet
						currentPlayer.setBet(currentPlayer.getBet() * 2);

						// Draw a new card and give it to the player
						cardDrawn = this.deck.getCard();
						this.server.queueMessage("# "
								+ (currentPlayer.getPlayerNo()) + " "
								+ cardDrawn.toString());
						currentPlayer.getPlayer().addCard(cardDrawn);

						if (currentPlayer.getPlayer().getHandValue() > 21) {
							// If the player bust, remove their bet from their
							// coins
							// Broadcast to the server that the player bust
							int newCoins = currentPlayer.getCoins()
									- currentPlayer.getBet();
							currentPlayer.setCoins(newCoins);
							currentPlayer.setBet(0);
							this.server.queueMessage("& "
									+ currentPlayer.getPlayerNo() + " bust "
									+ newCoins);

						} else if (currentPlayer.getPlayer().getHandValue() == 21) {
							// If the player got blackjack, add their bet to
							// their coins
							// Broadcast to the server that the player got a
							// blackjack
							int newCoins = currentPlayer.getCoins()
									+ currentPlayer.getBet();
							currentPlayer.setCoins(newCoins);
							currentPlayer.setBet(0);
							this.server.queueMessage("& "
									+ currentPlayer.getPlayerNo()
									+ " blackjack " + newCoins);
						} else {
							// If the player didn't bust or get a blackjack, set
							// them to stand
							currentPlayer.getPlayer().setCurrentMove('S');
							this.server.queueMessage("& "
									+ currentPlayer.getPlayerNo() + " stand "
									+ currentPlayer.getCoins());
						}

						// End the player's turn
						endTurn = true;
					}
				}
			}

			// Run a small delay to make sure all values are updated across all
			// threads
			try {
				Thread.sleep(Server.MESSAGE_DELAY);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			// Show the dealer's hidden card
			this.server.queueMessage("# 0 " + hiddenCard.toString());
			this.dealTheDealer(hiddenCard);

			// Keep drawing cards for the dealer until the dealer hits 17 or
			// higher
			// Broadcast each card as the dealer draws
			while (this.dealerHand < 17) {
				try {
					Thread.sleep(Server.MESSAGE_DELAY);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				cardDrawn = this.deck.getCard();
				this.server.queueMessage("# 0 " + cardDrawn.toString());
				this.dealTheDealer(cardDrawn);
			}

			// Bust the dealer if the dealer gets over 21 (basically guarantee
			// win by making the value -1)

			// If the dealer gets blackjack or is still less than 21, broadcast
			// the corresponding message
			if (this.dealerHand > 21) {
				this.dealerHand = -1;
				this.server.queueMessage("& 0 bust X");
			} else if (this.dealerHand == 21) {
				this.server.queueMessage("& 0 blackjack X");
			} else {
				this.server.queueMessage("& 0 stand X");
			}

			// Check for winners amongst all the players who said to stand
			for (Client player : this.players) {
				if (player.isPlayer()
						&& player.getPlayer().getCurrentMove() == 'S') {
					this.checkResult(player);
				}
			}

			// Set a small delay to make sure everything updates
			try {
				Thread.sleep(Server.MESSAGE_DELAY);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			// Loop through each player and get their coins, adding it to the
			// standings string. Broadcasts the string at the end.
			String standings = "+ ";
			for (Client player : this.players) {
				standings += (player.getPlayerNo() + " " + player.getPlayer()
						.getCoins()) + " ";
			}
			this.server.queueMessage(standings);

			// Clear the cards of each player including the dealer
			this.dealerCards.clear();
			this.dealerHand = 0;

			for (Client player : this.players) {
				if (player.isPlayer()) {
					player.getPlayer().clearHand();
				}
			}

			// Shuffle deck and broadcast the message
			if (this.deck.size() < MINIMUM_CARDS_PER_PLAYER
					* this.players.size()
					|| Math.random() * 100 < SHUFFLE_CHANCE) {
				this.deck.reloadDeck();
				this.server.queueMessage("% SHUFFLE");
			}

			// Disconnect all players who do not have enough coins to continue
			// playing and print the information to the console
			for (Client currentPlayer : this.players) {
				if (currentPlayer.getPlayer().getCoins() < Server.MIN_BET) {
					this.server.println("Disconnecting player from server");
					this.server.disconnectPlayer(currentPlayer);
				}
			}
		}

		// End the game when there are no more players
		this.server.println("All players have left.\nGame over.");
		this.server.endGame();
	}

	/**
	 * Handles dealing to the dealer and updates the current hand's value.
	 * 
	 * @param card
	 *            the card to deal to the dealer.
	 */
	public void dealTheDealer(Card card) {
		this.dealerCards.add(card);
		int handTotal = 0;

		// If the cards in the hand busts, try to keep deranking aces until it
		// stops busting
		boolean tryDeranking = true;
		while ((handTotal = this.calculateHand(this.dealerCards)) > 21
				&& tryDeranking) {
			tryDeranking = false;
			for (int cardNo = 0; cardNo < this.dealerCards.size(); cardNo++) {
				if (this.dealerCards.get(cardNo).derankAce()) {
					tryDeranking = true;
					break;
				}
			}
		}

		// Update the dealer's total value
		this.dealerHand = handTotal;
		this.server.println("Dealer hand value: " + this.dealerHand);
	}

	/**
	 * Sums up the current value of a hand.
	 * 
	 * @param cards
	 *            a list of cards.
	 */
	private int calculateHand(ArrayList<Card> cards) {
		int total = 0;
		for (int cardNo = 0; cardNo < cards.size(); cardNo++) {
			total += cards.get(cardNo).getValue();
		}
		return total;
	}

	/**
	 * Gets the deck of cards.
	 * 
	 * @return the deck of cards.
	 */
	public Deck getDeck() {
		return this.deck;
	}

	/**
	 * Getter for whether the game is currently in the betting state.
	 * 
	 * @return whether or not the game is currently in the betting state.
	 */
	public boolean bettingIsActive() {
		return this.bettingIsActive;
	}

	/**
	 * Getter for the player number of the player whose turn it is.
	 * 
	 * @return the player number of the player whose turn it is.
	 */
	public int getCurrentPlayerTurn() {
		return this.currentPlayerTurn;
	}

	/**
	 * Checks for the winner.
	 * 
	 * @param playerNo
	 *            the player number of the player to check.
	 */
	public void checkResult(Client client) {
		// If the player gets anything closer to the blackjack than the
		// dealer they win, and dealer wins on a tie
		Player player = client.getPlayer();
		if (player.getHandValue() > this.dealerHand) {
			player.setCoins(player.getCoins() + player.getCurrentBet());
		} else {
			player.setCoins(player.getCoins() - player.getCurrentBet());
		}
	}
}