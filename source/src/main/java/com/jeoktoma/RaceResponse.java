package com.jeoktoma;

public class RaceResponse {
    private String status;
    private int timer;
    private String winnerHorse;
    private String topWinner;
    private String serverIp;
    private java.util.List<Horse> horses;
    
    // 생성자, getter, setter
    public RaceResponse() {}
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public int getTimer() { return timer; }
    public void setTimer(int timer) { this.timer = timer; }
    
    public String getWinnerHorse() { return winnerHorse; }
    public void setWinnerHorse(String winnerHorse) { this.winnerHorse = winnerHorse; }
    
    public String getTopWinner() { return topWinner; }
    public void setTopWinner(String topWinner) { this.topWinner = topWinner; }
    
    public String getServerIp() { return serverIp; }
    public void setServerIp(String serverIp) { this.serverIp = serverIp; }
    
    public java.util.List<Horse> getHorses() { return horses; }
    public void setHorses(java.util.List<Horse> horses) { this.horses = horses; }
}
