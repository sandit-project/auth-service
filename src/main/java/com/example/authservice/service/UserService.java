package com.example.authservice.service;

import com.example.authservice.client.AiGrpcClient;
import com.example.authservice.config.redis.RedisUtil;
import com.example.authservice.config.security.CustomUserDetails;
import com.example.authservice.dto.*;
import com.example.authservice.exception.EmailNotVerifiedException;
import com.example.authservice.mapper.AddressMapper;
import com.example.authservice.mapper.UserMapper;
import com.example.authservice.model.Address;
import com.example.authservice.model.Social;
import com.example.authservice.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static com.example.authservice.type.Role.ROLE_USER;
import static com.example.authservice.type.Type.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final AddressMapper addressMapper;
    private final AuthenticationManager authenticationManager;
    private final TokenProviderService tokenProviderService;
    private final EmailService emailService;
    private final RedisUtil redisUtil;
    private final AiGrpcClient aiGrpcClient;

    @Transactional
    public UserLoginResponseDTO login(String username, String password) {
        System.out.println("login info is :: " + username + " " + password);

        Authentication authenticate = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        SecurityContextHolder.getContext().setAuthentication(authenticate);

        User user = ((CustomUserDetails) authenticate.getPrincipal()).getUser();

        String accessToken = tokenProviderService.generateToken(user, Duration.ofHours(2));
        String refreshToken = tokenProviderService.generateToken(user, Duration.ofDays(2));

        System.out.println("accessToken is :: " + accessToken);
        System.out.println("refreshToken is :: " + refreshToken);

        // redis에 저장
        tokenProviderService.saveTokensToRedis("USER:"+user.getUserId(), accessToken, refreshToken);

        // DB에 저장
        tokenProviderService.saveTokenToDatabase("user",user.getUid(), accessToken, refreshToken);

        return UserLoginResponseDTO.builder()
                .loggedIn(true)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .userName(user.getUserName())
                .build();
    }

    @Transactional
    public boolean existsByUserId(String userId) {
        return userMapper.findUserByUserId(userId) != null;
    }

    @Transactional
    public UserJoinResponseDTO join(UserJoinRequestDTO userJoinRequestDTO) {
        // 1) 이메일 인증 체크
        String email = userJoinRequestDTO.getEmail();
        if (!redisUtil.existData(email + ":verified") ||
                !"true".equals(redisUtil.getData(email + ":verified"))) {
            throw new EmailNotVerifiedException("이메일 인증이 필요합니다.");
        }

        String userId = userJoinRequestDTO.getUserId();

        // 2) 아이디 중복 검사
        if (existsByUserId(userId)) {
            throw new IllegalArgumentException("이미 사용중인 아이디입니다.");
        }

        // 3) User/Address 엔티티 변환 및 저장
        User user = userJoinRequestDTO.toUser(new BCryptPasswordEncoder()); // toUser()는 DTO에서 엔티티로 변환하는 메서드
        try {
            userMapper.insertUser(user);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("이미 사용중인 아이디입니다.", ex);
        }

        if (user.getUid() == 0) {
            throw new RuntimeException("사용자 UID 생성 실패");
        }

        Address address = userJoinRequestDTO.toAddress();
        address.setUserUid(user.getUid());
        int addressResult = addressMapper.insertAddress(address);
        if (addressResult <= 0) {
            throw new RuntimeException("주소 저장에 실패했습니다.");
        }

        // 4) 이메일 인증 정보 삭제
        redisUtil.deleteData(email + ":verified");

        // 5) 알러지 정보 AI 서비스로 전송 (DTO에서 추출)
        // notifyAiAboutAllergy(userJoinRequestDTO, user.getUid());

        // 6) 응답 DTO 생성 및 반환
        UserJoinResponseDTO response = UserJoinResponseDTO.builder()
                .isSuccess(true)
                .message("회원가입 성공")
                .userUid(user.getUid())
                .build();

        log.debug("[디버그] 응답 DTO: {}", response);
        return response;
    }

    // 트랜잭션 밖에서 Ai서비스로 알러지 정보 전송
    public void notifyAiAboutAllergy(UserJoinRequestDTO dto, int userUid) {
        try {
            aiGrpcClient.sendAllergyInfo(dto, userUid);
        } catch (Exception e) {
            log.warn("AI 알러지 전송 실패 후 사용자 롤백 처리");
            addressMapper.finalDeleteUserAddress(userUid);
            userMapper.finalDeleteUser(userUid);
        }
    }


    @Transactional
    public OAuthLoginResponseDTO oauthLogin(OAuthLoginRequestDTO oauthDTO){
        
        String[] tokens = oauthDTO.getAccessToken().split(":");

        Social findSocial;

        if("naver".equals(tokens[0])){
            findSocial = userMapper.findSocialByUserName(oauthDTO.getName());
        }else if("google".equals(tokens[0])){
            findSocial = userMapper.findSocialByUserName(oauthDTO.getName());
        }else if("kakao".equals(tokens[0])){
            // 지금은 정보요청 권한이 없어서 닉네임만 받아올수 있음
            findSocial = userMapper.findSocialByUserName(oauthDTO.getNickname());
        }else{
            return OAuthLoginResponseDTO.builder()
                    .loggedIn(false)
                    .message("ForbiddenToken")
                    .build();
        }

        if(findSocial == null){
            // 회원정보 저장 후 로그인 처리
            Social newSocial = buildNewSocialObj(tokens[0], oauthDTO);

            if(newSocial != null){
                int result = userMapper.saveSocial(newSocial);
                System.out.println("DB 회원정보 저장함");
                if(result == 1){
                    Social social = userMapper.findSocialByUserId(newSocial.getUserId());
                    // redis에 저장
                    tokenProviderService.saveTokensToRedis(tokens[0].toUpperCase()+":"+oauthDTO.getId(), oauthDTO.getAccessToken(), oauthDTO.getRefreshToken());
                    System.out.println("redis 토큰 저장함");
                    // DB에 저장
                    tokenProviderService.saveTokenToDatabase("social",social.getUid(),oauthDTO.getAccessToken(),oauthDTO.getRefreshToken());
                    System.out.println("DB 토큰 저장함");
                    return OAuthLoginResponseDTO.builder()
                            .loggedIn(true)
                            .type(newSocial.getType())
                            .userName(newSocial.getUserName())
                            .email(newSocial.getEmail())
                            .mobile(newSocial.getPhone())
                            .role(newSocial.getRole())
                            .accessToken(oauthDTO.getAccessToken())
                            .refreshToken(oauthDTO.getRefreshToken())
                            .build();
                }else{
                    return OAuthLoginResponseDTO.builder()
                            .loggedIn(false)
                            .build();
                }
            }else{
                return OAuthLoginResponseDTO.builder()
                        .loggedIn(false)
                        .message("Forbidden")
                        .build();
            }
        }else{
            // accessToken의 타입 잘라서 타입이 일치하면 로그인 처리
            // 다른 타입이면 이미 가입한 계정이 있다고 응답
            System.out.println("find type is :: " + findSocial.getType().name());
            System.out.println("tokens type is :: " + tokens[0]);
            if(findSocial.getType().name().toLowerCase().equals(tokens[0])){
                if(findSocial.getStatus().equals("deleted")){
                    userMapper.activeSocial(findSocial.getUserId());
                }
                // redis에 저장
                tokenProviderService.saveTokensToRedis(findSocial.getType().name()+":"+oauthDTO.getId(), oauthDTO.getAccessToken(), oauthDTO.getRefreshToken());

                // DB에 저장
                tokenProviderService.saveTokenToDatabase("social",findSocial.getUid(),oauthDTO.getAccessToken(),oauthDTO.getRefreshToken());
                
                return OAuthLoginResponseDTO.builder()
                            .loggedIn(true)
                            .type(findSocial.getType())
                            .userName(findSocial.getUserName())
                            .email(findSocial.getEmail())
                            .mobile(findSocial.getPhone())
                            .role(findSocial.getRole())
                            .accessToken(oauthDTO.getAccessToken())
                            .refreshToken(oauthDTO.getRefreshToken())
                            .build();
            }else{
                return OAuthLoginResponseDTO.builder()
                        .loggedIn(false)
                        .type(findSocial.getType())
                        .message("AlreadyExists")
                        .build();
            }
        }
    }

    protected Social buildNewSocialObj (String type, OAuthLoginRequestDTO oauthDTO) {
        if("naver".equals(type)){
            return Social.builder()
                    .userId(oauthDTO.getId())
                    .userName(oauthDTO.getName())
                    .email("")
                    .emailyn("n")
                    .phone(oauthDTO.getMobile())
                    .phoneyn("n")
                    .type(NAVER)
                    .role(ROLE_USER)
                    .build();
        }else if("google".equals(type)){
            return Social.builder()
                    .userId(oauthDTO.getId())
                    .userName(oauthDTO.getName())
                    .email(oauthDTO.getEmail())
                    .emailyn("n")
                    .phone("")
                    .phoneyn("n")
                    .type(GOOGLE)
                    .role(ROLE_USER)
                    .build();
        }else if("kakao".equals(type)){
            // 지금은 정보요청 권한이 없어서 닉네임만 받아올수 있음
            return Social.builder()
                    .userId(oauthDTO.getId())
                    .userName(oauthDTO.getNickname())
                    .email("")
                    .emailyn("n")
                    .phone("")
                    .phoneyn("n")
                    .type(KAKAO)
                    .role(ROLE_USER)
                    .build();
        }else{
            return null;
        }
    }

    public LogoutResponseDTO logout(String token) {
        String[] splitArr = token.split(":");

        boolean isSocial = splitArr[0].equals("kakao")
                || splitArr[0].equals("naver")
                || splitArr[0].equals("google");

        boolean redisResult;
        boolean dbResult;

        if(isSocial){
            redisResult = tokenProviderService.deleteTokenToRedis(splitArr[0].toUpperCase(),splitArr[1]);
            System.out.println("userId is :: " + splitArr[1]);
            Social findSocial = userMapper.findSocialByUserId(splitArr[1]);
            dbResult = tokenProviderService.deleteTokenToDatabase("social",findSocial.getUid());
        }else{
            String resultUserId = tokenProviderService.getTokenDetails(token).getUserId();
            redisResult = tokenProviderService.deleteTokenToRedis("USER",resultUserId);
            User user = userMapper.findUserByUserId(resultUserId);
            dbResult = tokenProviderService.deleteTokenToDatabase("user",user.getUid());
        }
        return redisResult&&dbResult?
                LogoutResponseDTO.builder()
                        .successed(true)
                        .build() :
                LogoutResponseDTO.builder()
                        .successed(false)
                        .build();
    }

    public UserInfoResponseDTO getUserInfo(String token) {
        String[] splitArr = token.split(":");

        boolean isSocial = splitArr[0].equals("kakao")
                || splitArr[0].equals("naver")
                || splitArr[0].equals("google");

        if(isSocial){
            Social findSocial = userMapper.findSocialByUserId(splitArr[1]);

            return UserInfoResponseDTO.builder()
                    .id(Long.valueOf(findSocial.getUid()))
                    .type("social")
                    .userId(findSocial.getUserId())
                    .userName(findSocial.getUserName())
                    .role(findSocial.getRole())
                    .build();
        }else{
            User tokenUserInfo = tokenProviderService.getTokenDetails(token);
            User findUser = userMapper.findUserByUserId(tokenUserInfo.getUserId());

            return UserInfoResponseDTO.builder()
                    .id(Long.valueOf(findUser.getUid()))
                    .type("user")
                    .userId(findUser.getUserId())
                    .userName(findUser.getUserName())
                    .role(findUser.getRole())
                    .build();
        }
    }

    public ProfileResponseDTO getUserProfile(String token) {
        String[] splitArr = token.split(":");

        boolean isSocial = splitArr[0].equals("kakao")
                || splitArr[0].equals("naver")
                || splitArr[0].equals("google");

        if(isSocial){
            Social findSocial = userMapper.findSocialByUserId(splitArr[1]);

            Address userAddress = addressMapper.findBySocialUid(findSocial.getUid());

            return ProfileResponseDTO.builder()
                    .uid(findSocial.getUid())
                    .userId(findSocial.getUserId())
                    .userName(findSocial.getUserName())
                    .email(findSocial.getEmail())
                    .emailyn(findSocial.getEmailyn())
                    .phone(findSocial.getPhone())
                    .phoneyn(findSocial.getPhoneyn())
                    .type(findSocial.getType())
                    .point(findSocial.getPoint())
                    .role(findSocial.getRole())
                    .createdDate(findSocial.getCreatedDate())
                    .mainAddress(userAddress!=null? userAddress.getMainAddress() : null)
                    .mainLat(userAddress!=null? userAddress.getMainLat() : 0)
                    .mainLan(userAddress!=null? userAddress.getMainLan() : 0)
                    .subAddress1(userAddress!=null? userAddress.getSubAddress1() : null)
                    .sub1Lat(userAddress!=null? userAddress.getSub1Lat() : 0)
                    .sub1Lan(userAddress!=null? userAddress.getSub1Lan() : 0)
                    .subAddress2(userAddress!=null? userAddress.getSubAddress2() : null)
                    .sub2Lat(userAddress!=null? userAddress.getSub2Lat() : 0)
                    .sub2Lan(userAddress!=null? userAddress.getSub2Lan() : 0)
                    .build();
        }else{
            User tokenUserInfo = tokenProviderService.getTokenDetails(token);
            User findUser = userMapper.findUserByUserId(tokenUserInfo.getUserId());

            Address userAddress = addressMapper.findByUserUid(findUser.getUid());

            return ProfileResponseDTO.builder()
                    .uid(findUser.getUid())
                    .userId(findUser.getUserId())
                    .userName(findUser.getUserName())
                    .email(findUser.getEmail())
                    .emailyn(findUser.getEmailyn())
                    .phone(findUser.getPhone())
                    .phoneyn(findUser.getPhoneyn())
                    .type(USER)
                    .point(findUser.getPoint())
                    .role(findUser.getRole())
                    .createdDate(findUser.getCreatedDate())
                    .mainAddress(userAddress!=null? userAddress.getMainAddress() : null)
                    .mainLat(userAddress!=null? userAddress.getMainLat() : 0)
                    .mainLan(userAddress!=null? userAddress.getMainLan() : 0)
                    .subAddress1(userAddress!=null? userAddress.getSubAddress1() : null)
                    .sub1Lat(userAddress!=null? userAddress.getSub1Lat() : 0)
                    .sub1Lan(userAddress!=null? userAddress.getSub1Lan() : 0)
                    .subAddress2(userAddress!=null? userAddress.getSubAddress2() : null)
                    .sub2Lat(userAddress!=null? userAddress.getSub2Lat() : 0)
                    .sub2Lan(userAddress!=null? userAddress.getSub2Lan() : 0)
                    .build();
        }
    }

    @Transactional
    public LogoutResponseDTO deleteAccount(String token) {
        LogoutResponseDTO removeTokenResult = logout(token);

        String[] splitArr = token.split(":");

        boolean isSocial = splitArr[0].equals("kakao")
                || splitArr[0].equals("naver")
                || splitArr[0].equals("google");

        int result = isSocial
                ? userMapper.deleteSocial(splitArr[1])
                : userMapper.deleteUser(tokenProviderService.getTokenDetails(token).getUserId());

        return LogoutResponseDTO.builder()
                .successed((result>0)&& removeTokenResult.isSuccessed())
                .build();
    }

    //주소 변경
    @Transactional
    public UpdateAddressResponseDTO updateAddress(String token, UpdateAddressRequestDTO request) {
        String[] parts = token.split(":");
        boolean isSocial = parts[0].equals("kakao")
                || parts[0].equals("naver")
                || parts[0].equals("google");

        // DB에서 기존 주소 불러오기
        Address address;

        if (isSocial) {
            int socialUid = userMapper.findSocialByUserId(parts[1]).getUid();
            address = addressMapper.findBySocialUid(socialUid);
            address.setSocialUid(socialUid);
        } else {
            int userUid = tokenProviderService.getTokenDetails(token).getUid();
            address = addressMapper.findByUserUid(userUid);
            address.setUserUid(userUid);
        }

        // Address 엔티티에 변경 사항 반영 - null 또는 0.0 아닐 때만 set
        if (request.getMainAddress() != null) address.setMainAddress(request.getMainAddress());
        if (request.getSubAddress1() != null) address.setSubAddress1(request.getSubAddress1());
        if (request.getSubAddress2() != null) address.setSubAddress2(request.getSubAddress2());

        if (request.getMainLat() != 0.0) address.setMainLat(request.getMainLat());
        if (request.getMainLan() != 0.0) address.setMainLan(request.getMainLan());
        if (request.getSubLat1() != 0.0) address.setSub1Lat(request.getSubLat1());
        if (request.getSubLan1() != 0.0) address.setSub1Lan(request.getSubLan1());
        if (request.getSubLat2() != 0.0) address.setSub2Lat(request.getSubLat2());
        if (request.getSubLan2() != 0.0) address.setSub2Lan(request.getSubLan2());

        log.info("주소 갱신 요청: {}", address);

        // DB 업데이트
        int updatedRows = isSocial
                ? addressMapper.updateAddressBySocialUid(address)
                : addressMapper.updateAddressByUserUid(address);


        boolean success = (updatedRows > 0);

        if (updatedRows == 0) {
            log.warn("주소 업데이트 실패: uid={}, isSocial={}, address={}",
                    isSocial ? address.getSocialUid() : address.getUserUid(),
                    isSocial,
                    address
            );
        }


        // 결과 DTO 리턴
        return UpdateAddressResponseDTO.builder()
                .isSuccess(success)
                .build();
    }

    @Transactional
    public boolean updateUserProfile(String token, UpdateProfileRequestDTO updateProfileRequestDTO) {
        String[] splitArr = token.split(":");

        boolean isSocial = "naver".equals(splitArr[0]) ||
                "kakao".equals(splitArr[0]) ||
                "google".equals(splitArr[0]);

        if(isSocial){
            Social findSocial = userMapper.findSocialByUserId(splitArr[1]);

            redisUtil.setObjectDataExpire("socialInfo:"+findSocial.getUid(), findSocial, 60 * 30L);

            boolean socialResult = userMapper.updateSocial(
                    Social.builder()
                            .userId(splitArr[1])
                            .userName(updateProfileRequestDTO.getUserName())
                            .email(updateProfileRequestDTO.getEmail())
                            .emailyn(updateProfileRequestDTO.getEmailyn())
                            .phone(updateProfileRequestDTO.getPhone())
                            .phoneyn(updateProfileRequestDTO.getPhoneyn())
                            .build()) > 0 ;
            Address findAddress = addressMapper.findBySocialUid(findSocial.getUid());

            redisUtil.setObjectDataExpire("socialAddressInfo:"+findSocial.getUid(), findAddress, 60 * 30L);

            boolean addressResult;
            if(findAddress == null){
                addressResult = addressMapper.insertAddress(
                        Address.builder()
                                .socialUid(findSocial.getUid())
                                .mainAddress(updateProfileRequestDTO.getMainAddress())
                                .mainLat(updateProfileRequestDTO.getMainLat())
                                .mainLan(updateProfileRequestDTO.getMainLan())
                                .subAddress1(updateProfileRequestDTO.getSubAddress1())
                                .sub1Lat(updateProfileRequestDTO.getSubLat1())
                                .sub1Lan(updateProfileRequestDTO.getSubLan1())
                                .subAddress2(updateProfileRequestDTO.getSubAddress2())
                                .sub2Lat(updateProfileRequestDTO.getSubLat2())
                                .sub2Lan(updateProfileRequestDTO.getSubLan2())
                                .build()) == 1;
            }else{
                addressResult = addressMapper.updateAddressBySocialUid(
                        Address.builder()
                                .socialUid(findSocial.getUid())
                                .mainAddress(updateProfileRequestDTO.getMainAddress())
                                .mainLat(updateProfileRequestDTO.getMainLat())
                                .mainLan(updateProfileRequestDTO.getMainLan())
                                .subAddress1(updateProfileRequestDTO.getSubAddress1())
                                .sub1Lat(updateProfileRequestDTO.getSubLat1())
                                .sub1Lan(updateProfileRequestDTO.getSubLan1())
                                .subAddress2(updateProfileRequestDTO.getSubAddress2())
                                .sub2Lat(updateProfileRequestDTO.getSubLat2())
                                .sub2Lan(updateProfileRequestDTO.getSubLan2())
                                .build()) > 0 ;
            }
            return socialResult && addressResult;
        }else{
            log.info("before excute user profile update");
            User findUser = tokenProviderService.getTokenDetails(token);
            log.info("defore save redis user info");
            redisUtil.setObjectDataExpire("userInfo:"+findUser.getUid(), findUser, 60 * 30L);
            log.info("after save redis user info");
            boolean userResult =  userMapper.updateUser(
                    User.builder()
                            .userId(findUser.getUserId())
                            .userName(updateProfileRequestDTO.getUserName())
                            .email(updateProfileRequestDTO.getEmail())
                            .emailyn(updateProfileRequestDTO.getEmailyn())
                            .phone(updateProfileRequestDTO.getPhone())
                            .phoneyn(updateProfileRequestDTO.getPhoneyn())
                            .build()) > 0 ;
            log.info("before excute address update");
            Address findAddress = addressMapper.findByUserUid(findUser.getUid());
            log.info("before save redis address update");
            redisUtil.setObjectDataExpire("userAddressInfo:"+findUser.getUid(), findAddress, 60 * 30L);

            boolean addressResult = addressMapper.updateAddressByUserUid(
                    Address.builder()
                            .userUid(findUser.getUid())
                            .mainAddress(updateProfileRequestDTO.getMainAddress())
                            .mainLat(updateProfileRequestDTO.getMainLat())
                            .mainLan(updateProfileRequestDTO.getMainLan())
                            .subAddress1(updateProfileRequestDTO.getSubAddress1())
                            .sub1Lat(updateProfileRequestDTO.getSubLat1())
                            .sub1Lan(updateProfileRequestDTO.getSubLan1())
                            .subAddress2(updateProfileRequestDTO.getSubAddress2())
                            .sub2Lat(updateProfileRequestDTO.getSubLat2())
                            .sub2Lan(updateProfileRequestDTO.getSubLan2())
                            .build()) > 0;
            return userResult && addressResult;
        }
    }

    // 트랜잭션 밖에서 Ai서비스로 알러지 정보 전송
    public void modifyAllergy(UpdateProfileRequestDTO dto, String token) {
        UpdateAllergyRequestDTO allergyInfo = null;
        boolean isSocial = token.startsWith("naver:") || token.startsWith("kakao:") || token.startsWith("google:");

        try {
            aiGrpcClient.updateAllergyInfo(dto, isSocial);
            // 캐시 삭제
            String prefix = isSocial ? "social" : "user";
            redisUtil.deleteData(prefix + "Info" + dto.getUid());
            redisUtil.deleteData(prefix + "AddressInfo" + dto.getUid());
        } catch (Exception e) {
            log.warn("알러지 수정 실패: {} 복구 시도", e.getMessage());

            if (isSocial) {
                Social backup = (Social) redisUtil.getObjectData("socialInfo" + dto.getUid());
                Address address = (Address) redisUtil.getObjectData("socialAddressInfo" + dto.getUid());
                userMapper.updateSocial(backup);
                addressMapper.updateAddressBySocialUid(address);
            } else {
                User backup = (User) redisUtil.getObjectData("userInfo" + dto.getUid());
                Address address = (Address) redisUtil.getObjectData("userAddressInfo" + dto.getUid());
                userMapper.updateUser(backup);
                addressMapper.updateAddressByUserUid(address);
            }
        }
    }

    public EmailService getEmailService() {
        return emailService;
    }

    public List<ManagerResponseDTO> getManagers() {
        return userMapper.findManagers();
    }
}
