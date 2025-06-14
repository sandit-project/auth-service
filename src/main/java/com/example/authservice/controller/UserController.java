package com.example.authservice.controller;

import com.example.authservice.dto.*;
import com.example.authservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/auths")
public class UserController {

    private final UserService userService;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    @PostMapping("/login")
    public UserLoginResponseDTO login(@RequestBody UserLoginRequestDTO userLoginRequestDTO) {
        log.info("login");
        return userService.login(userLoginRequestDTO.getUserId(), userLoginRequestDTO.getPassword());
    }

    @GetMapping("/check-id")
    public ResponseEntity<Map<String,Boolean>> checkUserId(@RequestParam String userId) {
        boolean exists = userService.existsByUserId(userId);
        log.info("check-id: " + userId);
        return ResponseEntity.ok(Collections.singletonMap("exists", exists));
    }
    // ROLL이 MANAGER인 유저정보(USER_UID,USER_ID,USER_NAME)가져오기
    @GetMapping("/managers")
    public ResponseEntity<List<ManagerResponseDTO>> getManagers() {
        List<ManagerResponseDTO> managers = userService.getManagers();
        log.info("managers : " + managers);
        return ResponseEntity.ok(managers);
    }

    @PostMapping("/join")
    public ResponseEntity<UserJoinResponseDTO> join(@RequestBody @Valid UserJoinRequestDTO userJoinRequestDTO) {
        log.info("join :: {} {}", userJoinRequestDTO.getUserName(), userJoinRequestDTO.getEmail());
        // 1.회원 정보/주소 저장 + UID 생성(여기까지가 트랜잭션)
        UserJoinResponseDTO response = userService.join(userJoinRequestDTO);
        // 2.트랜잭션 커밋 후 알러지 정보 전송
        userService.notifyAiAboutAllergy(userJoinRequestDTO,response.getUserUid());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/login/oauth")
    public OAuthLoginResponseDTO socialLogin(@RequestBody OAuthLoginRequestDTO oauthLoginRequestDTO) {
        log.info("oauth login :: {}", oauthLoginRequestDTO.getAccessToken());
        return userService.oauthLogin(oauthLoginRequestDTO);
    }

    @PostMapping("/user/info")
    public UserInfoResponseDTO getUserInfo(HttpServletRequest request){
        String token = request.getHeader("Authorization").substring(7);
        log.info("user info :: {}", token);
        return userService.getUserInfo(token);
    }

    @PostMapping("/profile")
    public ProfileResponseDTO getUserProfile(HttpServletRequest request){
        String token = request.getHeader("Authorization").substring(7);
        log.info("user profile :: {}", token);
        return userService.getUserProfile(token);
    }

    @PutMapping("/profile")
    public boolean updateUserProfile(HttpServletRequest request, @RequestBody UpdateProfileRequestDTO updateProfileRequestDTO){
        String token = request.getHeader("Authorization").substring(7);
        log.info("user update profile :: {}", token);
        boolean result = userService.updateUserProfile(token, updateProfileRequestDTO);
        userService.modifyAllergy(updateProfileRequestDTO, token);
        return result;
    }

    @PostMapping("/logout")
    public LogoutResponseDTO logout(HttpServletRequest request){
        String token = request.getHeader("Authorization").substring(7);
        log.info("logout :: {}",token);
        return userService.logout(token);
    }

    @DeleteMapping("/user")
    public LogoutResponseDTO deleteAccount(HttpServletRequest request){
        String token = request.getHeader("Authorization").substring(7);
        log.info("delete account :: {}", token);
        return userService.deleteAccount(token);
    }

    //주소 수정
//    @PutMapping("/address")
//    public UpdateAddressResponseDTO updateUserAddress(@RequestHeader("Authorization") String bearerToken, @RequestBody UpdateAddressRequestDTO request) {
//        String token = bearerToken.substring(7);
//        log.info("update user address :: {}", request);
//        return userService.updateAddress(token,request);
//    }

    @PutMapping("/address")
    public UpdateAddressResponseDTO updateUserAddress(HttpServletRequest httpServletRequest, @RequestBody UpdateAddressRequestDTO request) {
        String token = httpServletRequest.getHeader("Authorization").substring(7);
        log.info("update user address :: {}", request);
        return userService.updateAddress(token,request);
    }
}
