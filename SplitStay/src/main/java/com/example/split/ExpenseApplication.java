package com.example.split;

import jakarta.persistence.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@SpringBootApplication
public class ExpenseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpenseApplication.class, args);
    }
}

// --- ENTITIES ---
@Entity @Table(name = "users")
class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(unique = true) private String email;
    private String password;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

@Entity @Table(name = "expense_groups")
class Group {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Long userId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "group_id")
    private List<Member> members = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "group_id")
    private List<Expense> expenses = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public List<Member> getMembers() { return members; }
    public List<Expense> getExpenses() { return expenses; }
}

@Entity
class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

@Entity
class Expense {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String description;
    private BigDecimal amount;
    private Long paidByMemberId;
    private String date;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "expense_id")
    private List<ExpenseShare> shares = new ArrayList<>();

    public Long getId() { return id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getPaidByMemberId() { return paidByMemberId; }
    public void setPaidByMemberId(Long paidByMemberId) { this.paidByMemberId = paidByMemberId; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public List<ExpenseShare> getShares() { return shares; }
}

@Entity
class ExpenseShare {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long owerMemberId;
    private BigDecimal shareAmount;
    private Boolean paid = false;

    public Long getId() { return id; }
    public Long getOwerMemberId() { return owerMemberId; }
    public void setOwerMemberId(Long owerMemberId) { this.owerMemberId = owerMemberId; }
    public BigDecimal getShareAmount() { return shareAmount; }
    public void setShareAmount(BigDecimal shareAmount) { this.shareAmount = shareAmount; }
    public Boolean isPaid() { return paid != null && paid; }
    public void setPaid(Boolean paid) { this.paid = paid; }
}

// --- REPOSITORIES ---
interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
interface GroupRepository extends JpaRepository<Group, Long> {
    List<Group> findByUserId(Long userId);
}
interface ExpenseRepository extends JpaRepository<Expense, Long> {}
interface ExpenseShareRepository extends JpaRepository<ExpenseShare, Long> {}

// --- MVC CONTROLLER ---
@Controller
class WebController {

    private final UserRepository userRepo;
    private final GroupRepository groupRepo;
    private final ExpenseRepository expenseRepo;
    private final ExpenseShareRepository shareRepo;

    public WebController(UserRepository userRepo, GroupRepository groupRepo, 
                         ExpenseRepository expenseRepo, ExpenseShareRepository shareRepo) {
        this.userRepo = userRepo;
        this.groupRepo = groupRepo;
        this.expenseRepo = expenseRepo;
        this.shareRepo = shareRepo;
    }

    @GetMapping("/")
    public String index(HttpSession session, @RequestParam(required = false) Long groupId, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        List<Group> groups = groupRepo.findByUserId(user.getId());
        model.addAttribute("user", user);
        model.addAttribute("groups", groups);

        if (!groups.isEmpty()) {
            Group currentGroup = (groupId != null) 
                ? groupRepo.findById(groupId).orElse(groups.get(0)) 
                : groups.get(0);

            model.addAttribute("currentGroup", currentGroup);

            Map<Long, String> memberNameMap = new HashMap<>();
            for (Member m : currentGroup.getMembers()) {
                memberNameMap.put(m.getId(), m.getName());
            }
            model.addAttribute("memberNameMap", memberNameMap);

            Map<Long, Double> totalSpentMap = new HashMap<>();
            for (Member m : currentGroup.getMembers()) {
                totalSpentMap.put(m.getId(), 0.0);
            }

            // 1. Build Pairwise Direct Debt Matrix: pairwiseDebts[owerId][creditorId]
            Map<Long, Map<Long, Double>> pairwiseDebts = new HashMap<>();
            for (Member m1 : currentGroup.getMembers()) {
                pairwiseDebts.put(m1.getId(), new HashMap<>());
                for (Member m2 : currentGroup.getMembers()) {
                    pairwiseDebts.get(m1.getId()).put(m2.getId(), 0.0);
                }
            }

            for (Expense e : currentGroup.getExpenses()) {
                Long payerId = e.getPaidByMemberId();
                if (payerId != null && e.getAmount() != null) {
                    totalSpentMap.put(payerId, totalSpentMap.getOrDefault(payerId, 0.0) + e.getAmount().doubleValue());
                }

                for (ExpenseShare share : e.getShares()) {
                    if (!share.isPaid() && payerId != null) {
                        double shareAmt = share.getShareAmount() != null ? share.getShareAmount().doubleValue() : 0.0;
                        Long owerId = share.getOwerMemberId();
                        
                        if (pairwiseDebts.containsKey(owerId) && pairwiseDebts.get(owerId).containsKey(payerId)) {
                            double currentDebt = pairwiseDebts.get(owerId).get(payerId);
                            pairwiseDebts.get(owerId).put(payerId, currentDebt + shareAmt);
                        }
                    }
                }
            }

            // 2. Net mutual debts directly between pairs (e.g. A <-> B)
            List<Member> memberList = currentGroup.getMembers();
            Map<Long, Double> finalNetBalanceMap = new HashMap<>();
            for (Member m : memberList) {
                finalNetBalanceMap.put(m.getId(), 0.0);
            }

            List<String> settlementStatements = new ArrayList<>();

            for (int i = 0; i < memberList.size(); i++) {
                for (int j = i + 1; j < memberList.size(); j++) {
                    Long id1 = memberList.get(i).getId();
                    Long id2 = memberList.get(j).getId();

                    double debt1To2 = pairwiseDebts.get(id1).get(id2); // 1 owes 2
                    double debt2To1 = pairwiseDebts.get(id2).get(id1); // 2 owes 1

                    if (debt1To2 > debt2To1) {
                        double net = debt1To2 - debt2To1;
                        finalNetBalanceMap.put(id1, finalNetBalanceMap.get(id1) - net);
                        finalNetBalanceMap.put(id2, finalNetBalanceMap.get(id2) + net);
                        settlementStatements.add(memberNameMap.get(id1) + " owes " + memberNameMap.get(id2) + " ₹" + String.format("%.2f", net));
                    } else if (debt2To1 > debt1To2) {
                        double net = debt2To1 - debt1To2;
                        finalNetBalanceMap.put(id2, finalNetBalanceMap.get(id2) - net);
                        finalNetBalanceMap.put(id1, finalNetBalanceMap.get(id1) + net);
                        settlementStatements.add(memberNameMap.get(id2) + " owes " + memberNameMap.get(id1) + " ₹" + String.format("%.2f", net));
                    }
                }
            }

            List<Map<String, Object>> memberStats = new ArrayList<>();
            for (Member m : currentGroup.getMembers()) {
                double totalSpent = totalSpentMap.getOrDefault(m.getId(), 0.0);
                double balance = finalNetBalanceMap.getOrDefault(m.getId(), 0.0);
                
                Map<String, Object> stat = new HashMap<>();
                stat.put("id", m.getId());
                stat.put("name", m.getName());
                stat.put("paid", totalSpent);
                stat.put("balance", balance);
                memberStats.add(stat);
            }
            model.addAttribute("memberStats", memberStats);
            model.addAttribute("settlements", settlementStatements);
        }

        return "dashboard";
    }

    @GetMapping("/login")
    public String loginPage() { return "login"; }

    @PostMapping("/login")
    public String handleLogin(@RequestParam String email, @RequestParam String password, HttpSession session, Model model) {
        Optional<User> userOpt = userRepo.findByEmail(email);
        if (userOpt.isPresent() && userOpt.get().getPassword().equals(password)) {
            session.setAttribute("user", userOpt.get());
            return "redirect:/";
        }
        model.addAttribute("error", "Invalid credentials!");
        return "login";
    }

    @PostMapping("/register")
    public String handleRegister(@RequestParam String name, @RequestParam String email, @RequestParam String password, Model model) {
        if (userRepo.findByEmail(email).isPresent()) {
            model.addAttribute("error", "Email already exists!");
            return "login";
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(password);
        userRepo.save(user);
        return "redirect:/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/groups/create")
    public String createGroup(@RequestParam String name, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";
        Group g = new Group();
        g.setName(name);
        g.setUserId(user.getId());
        groupRepo.save(g);
        return "redirect:/";
    }

    @PostMapping("/members/add")
    public String addMember(@RequestParam Long groupId, @RequestParam String name) {
        Group g = groupRepo.findById(groupId).orElseThrow();
        Member m = new Member();
        m.setName(name);
        g.getMembers().add(m);
        groupRepo.save(g);
        return "redirect:/?groupId=" + groupId;
    }

    @PostMapping("/members/delete")
    public String deleteMember(@RequestParam Long memberId, @RequestParam Long groupId) {
        Group g = groupRepo.findById(groupId).orElse(null);
        if (g != null) {
            g.getMembers().removeIf(m -> m.getId().equals(memberId));
            for (Expense e : g.getExpenses()) {
                e.getShares().removeIf(s -> s.getOwerMemberId().equals(memberId));
            }
            groupRepo.save(g);
        }
        return "redirect:/?groupId=" + groupId;
    }

    @PostMapping("/expenses/add")
    public String addExpense(@RequestParam Long groupId, @RequestParam String description, 
                             @RequestParam BigDecimal amount, @RequestParam Long paidByMemberId, 
                             @RequestParam String date) {
        Group g = groupRepo.findById(groupId).orElseThrow();
        Expense e = new Expense();
        e.setDescription(description);
        e.setAmount(amount);
        e.setPaidByMemberId(paidByMemberId);
        e.setDate(date);

        int memberCount = g.getMembers().size();
        if (memberCount > 0) {
            BigDecimal perPersonShare = amount.divide(BigDecimal.valueOf(memberCount), 2, RoundingMode.HALF_UP);
            for (Member m : g.getMembers()) {
                if (!m.getId().equals(paidByMemberId)) {
                    ExpenseShare share = new ExpenseShare();
                    share.setOwerMemberId(m.getId());
                    share.setShareAmount(perPersonShare);
                    share.setPaid(false);
                    e.getShares().add(share);
                }
            }
        }

        g.getExpenses().add(e);
        groupRepo.save(g);
        return "redirect:/?groupId=" + groupId;
    }

    @PostMapping("/shares/toggle-paid")
    public String toggleSharePaid(@RequestParam Long shareId, @RequestParam Long groupId) {
        ExpenseShare share = shareRepo.findById(shareId).orElse(null);
        if (share != null) {
            share.setPaid(!share.isPaid());
            shareRepo.save(share);
        }
        return "redirect:/?groupId=" + groupId;
    }

    @PostMapping("/expenses/clear")
    public String clearExpenses(@RequestParam Long groupId) {
        Group group = groupRepo.findById(groupId).orElse(null);
        if (group != null) {
            group.getExpenses().clear();
            groupRepo.save(group);
        }
        return "redirect:/?groupId=" + groupId;
    }
}
